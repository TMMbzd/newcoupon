package com.newcoupon.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.newcoupon.application.service.OpenAppService;
import com.newcoupon.application.service.OpenAppService.CreateAppRequest;
import com.newcoupon.integration.OpenApiDriftComparator.Rule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * OpenAPI 契约测试（V6 2B-2，ADR-0022）——把"v1 冻结、只增不改"从 ADR 里的一句话变成 CI 证据。
 *
 * <p><b>四个守卫</b>：
 * <ol>
 *   <li><b>泄漏防守</b>：live spec（springdoc 按 {@code pathsToMatch("/open/v1/**")} 生成的
 *       open-v1 分组）里所有 path 必须以 /open/v1/ 开头——Controller 是双前缀映射
 *       （/api + /open/v1 同一个处理器），分组一旦失效，管理面端点就进了"对外契约"；</li>
 *   <li><b>防漂移</b>：live spec 对基线 docs/open-api/openapi-v1.json 做结构化对比
 *       （{@link OpenApiDriftComparator} 的显式规则集），破坏性变更红、additive 变更绿；</li>
 *   <li><b>黄金样例</b>：真实响应与 spec 契约一致（GET /open/v1/templates/{id} 关键字段级），
 *       以及 6010 版本拒绝的响应形状（data.supportedVersions）；</li>
 *   <li><b>安全面</b>：spec 端点 /api/api-docs/** 匿名 401（它挪进 /api 前缀就是为了吃管理面认证），
 *       认证成功的开放面响应带 X-API-Version 头。</li>
 * </ol>
 *
 * <p><b>基线的再生成</b>（改契约 = 先红后绿更新基线）：
 * {@code mvn test -Dtest=OpenApiContractIntegrationTest -Dopenapi.baseline.update=true}
 * 会用 live spec 重写基线文件，diff 审查后随代码一起提交（纪律见 docs/open-api/README.md）。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "coupon.open.secret-master-key=YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=",
                "coupon.open.secret-rotation-hours=168"
        })
class OpenApiContractIntegrationTest extends ShardedIntegrationTestSupport {

    /** 基线文件（相对项目根；surefire 工作目录=项目根） */
    static final Path BASELINE = Paths.get("docs/open-api/openapi-v1.json");

    /** dev profile 的管理面便利令牌（application-dev.yml，与 OpenApiOwnershipIntegrationTest 同款） */
    private static final String ADMIN_TOKEN = "dev-admin-token";

    /** 专供比较与解析的 mapper：不依赖 Spring 定制，测试自己拥有它的行为 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final String APP_ID = "t-contract";

    @org.springframework.test.context.DynamicPropertySource
    static void shardingProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("coupon.sharding.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai");
        registry.add("coupon.sharding.username", MYSQL::getUsername);
        registry.add("coupon.sharding.password", MYSQL::getPassword);
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private OpenAppService appService;

    private static OpenApiTestClient client;
    private static String secret;
    private static Long templateId;
    private static final AtomicBoolean PROVISIONED = new AtomicBoolean();

    @BeforeEach
    void provisionOnce() throws Exception {
        if (!PROVISIONED.compareAndSet(false, true)) {
            return;
        }
        client = new OpenApiTestClient(port);
        secret = appService.create(new CreateAppRequest(APP_ID, "契约测试接入方", null, null, null)).secret();

        // 真实链路建一张券模板（黄金样例用 GET /open/v1/templates/{id} 对拍）
        OpenApiTestClient.Response created = client.post("/open/v1/templates", """
                {"name":"契约验证券","activityId":1001,"couponType":1,"faceValue":1000,
                 "threshold":10000,"totalCount":100,"perUserLimit":5,
                 "validType":2,"validDays":7}""", APP_ID, secret, "contract-create-1");
        assertThat(created.status()).as("模板创建要成功，黄金样例才有东西可拍：" + created.body())
                .isEqualTo(200);
        templateId = JSON.readTree(created.body()).path("data").path("id").asLong();
    }

    // ------------------------------------------------------------------
    // 守卫 1：泄漏防守——对外契约里不允许出现非 /open/v1/ 的 path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("泄漏防守：spec 所有 path 以 /open/v1/ 开头（管理面/内部端点不得进对外契约）")
    void spec_containsOnlyOpenV1Paths() throws Exception {
        JsonNode paths = fetchLiveSpec().path("paths");

        assertThat(paths.size()).as("spec 不该是空的——空 spec 会让本用例假绿").isGreaterThan(0);

        List<String> leaked = new ArrayList<>();
        paths.fieldNames().forEachRemaining(p -> {
            if (!p.startsWith("/open/v1/")) {
                leaked.add(p);
            }
        });
        assertThat(leaked)
                .as("open-v1 分组里混入了非开放面 path（springdoc pathsToMatch 失效或新端点没落 /open/v1 前缀）")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // 守卫 2：防漂移——live 对基线只增不改
    // ------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.condition.DisabledIfSystemProperty(
            named = "openapi.baseline.update", matches = "true")   // 再生成的那一轮跑门禁没有意义
    @DisplayName("防漂移：live spec 对基线 docs/open-api/openapi-v1.json 无破坏性变更（只增不改门禁）")
    void liveSpec_matchesFrozenBaseline() throws Exception {
        JsonNode baseline = loadBaseline();
        JsonNode live = fetchLiveSpec();

        List<String> violations = OpenApiDriftComparator.diff(baseline, live);

        assertThat(violations)
                .as("v1 契约出现破坏性变更（v1 冻结=只增不改；破坏性变更必须走 /open/v2）。变更清单：\n%s\n"
                        + "若本次变更确属 additive 而基线未收录，先跑基线再生成并审查 diff（README.md 的纪律）",
                        String.join("\n", violations))
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // 守卫 2b：比较器自检——每条规则都能抓到对应的破坏性变异、都不误伤 additive 变异
    // ------------------------------------------------------------------

    /**
     * 合成 mini spec：覆盖参数（含 enum 与 pathItem 级共享参数）/请求体（数组 items、Map 值、
     * 约束键、枚举）/响应（枚举、约束、组合成员）/$ref 嵌套的最小可读样本。
     * 自检变异表必须覆盖比较器的全部 18 条规则与全部下钻路径（items/additionalProperties/组合）。
     */
    private static final String MINI_SPEC = """
            {
              "openapi": "3.0.3",
              "paths": {
                "/open/v1/templates/{id}": {
                  "parameters": [
                    {"name": "X-Trace-Id", "in": "header", "required": false, "schema": {"type": "string"}}
                  ],
                  "get": {
                    "summary": "查询模板",
                    "parameters": [
                      {"name": "id", "in": "path", "required": true, "schema": {"type": "integer"}},
                      {"name": "verbose", "in": "query", "required": false,
                       "schema": {"type": "string", "enum": ["full", "brief"]}}
                    ],
                    "responses": {
                      "200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/ResultTemplate"}}}},
                      "404": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/ResultTemplate"}}}}
                    }
                  },
                  "post": {
                    "requestBody": {"required": false, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/TemplateUpsert"}}}},
                    "responses": {"200": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/ResultTemplate"}}}}}
                  }
                }
              },
              "components": {
                "schemas": {
                  "ResultTemplate": {"type": "object", "required": ["code"], "properties": {
                    "code": {"type": "integer"},
                    "message": {"type": "string"},
                    "data": {"$ref": "#/components/schemas/Template"}
                  }},
                  "Template": {"type": "object", "properties": {
                    "id": {"type": "integer"},
                    "name": {"type": "string", "maxLength": 64},
                    "status": {"type": "string", "enum": ["DRAFT", "PUBLISHED", "OFFLINE"]},
                    "group": {"anyOf": [
                      {"type": "string", "maxLength": 10},
                      {"type": "integer"}
                    ]}
                  }},
                  "TemplateUpsert": {"type": "object", "required": ["name"], "properties": {
                    "name": {"type": "string"},
                    "tag": {"type": "string"},
                    "priority": {"type": "string", "enum": ["high", "low"]},
                    "meta": {"type": "object", "additionalProperties": {"type": "string"}},
                    "tags": {"type": "array", "maxItems": 10, "items": {"$ref": "#/components/schemas/Tag"}}
                  }},
                  "Tag": {"type": "object", "properties": {
                    "key": {"type": "string", "maxLength": 8},
                    "weight": {"type": "integer", "minimum": 0}
                  }}
                }
              }
            }
            """;

    /** mini spec 里最常用的节点定位（变异表里反复出现，收成局部变量免得每行都写一遍长链） */
    private static JsonNode item(JsonNode n) {
        return n.path("paths").path("/open/v1/templates/{id}");
    }

    private static JsonNode schema(JsonNode n, String name) {
        return n.path("components").path("schemas").path(name);
    }

    private record Mutation(String name, Rule expected, Consumer<JsonNode> mutation) {
    }

    @Test
    @DisplayName("比较器自检（破坏性）：33 类变异逐条命中自己的规则（覆盖全部 18 规则与 items/Map/组合下钻）")
    void comparator_flagsEveryBreakingMutation() throws Exception {
        List<Mutation> mutations = List.of(
                // ---- path / method / 参数 ----
                new Mutation("删 path", Rule.PATH_REMOVED,
                        n -> ((ObjectNode) n.path("paths")).remove("/open/v1/templates/{id}")),
                new Mutation("删 method", Rule.OPERATION_REMOVED,
                        n -> ((ObjectNode) item(n)).remove("get")),
                new Mutation("删参数", Rule.PARAM_REMOVED,
                        n -> ((ArrayNode) item(n).path("get").path("parameters")).remove(0)),
                new Mutation("参数类型变化", Rule.PARAM_TYPE_CHANGED,
                        n -> ((ObjectNode) item(n).path("get").path("parameters").get(0).path("schema"))
                                .put("type", "string")),
                new Mutation("参数可选变必填", Rule.PARAM_NEWLY_REQUIRED,
                        n -> ((ObjectNode) item(n).path("get").path("parameters").get(1))
                                .put("required", true)),
                new Mutation("新增必填参数", Rule.PARAM_ADDED_REQUIRED,
                        n -> ((ArrayNode) item(n).path("get").path("parameters")).add(JSON.createObjectNode()
                                .put("name", "force").put("in", "query").put("required", true)
                                .set("schema", JSON.createObjectNode().put("type", "string")))),
                new Mutation("参数枚举收窄", Rule.ENUM_NARROWED,
                        n -> ((ArrayNode) item(n).path("get").path("parameters").get(1)
                                .path("schema").path("enum")).remove(1)),
                new Mutation("pathItem 级共享参数删除", Rule.PARAM_REMOVED,
                        n -> ((ArrayNode) item(n).path("parameters")).remove(0)),
                // ---- 请求体 ----
                new Mutation("请求体变必填", Rule.REQUEST_BODY_NEWLY_REQUIRED,
                        n -> ((ObjectNode) item(n).path("post").path("requestBody")).put("required", true)),
                new Mutation("请求体新增必填字段", Rule.REQUEST_FIELD_NEWLY_REQUIRED,
                        n -> ((ArrayNode) schema(n, "TemplateUpsert").path("required")).add("tag")),
                new Mutation("请求体字段删除", Rule.REQUEST_FIELD_REMOVED,
                        n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties")).remove("name")),
                new Mutation("请求体字段类型变化", Rule.REQUEST_FIELD_TYPE_CHANGED,
                        n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties")
                                .path("tag")).put("type", "integer")),
                new Mutation("请求枚举约束新增（裸 string 加 enum）", Rule.ENUM_ADDED_CONSTRAINT,
                        n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties").path("tag"))
                                .set("enum", JSON.createArrayNode().add("a").add("b"))),
                // ---- items（数组元素） ----
                new Mutation("items 元素字段删除", Rule.REQUEST_FIELD_REMOVED,
                        n -> ((ObjectNode) schema(n, "Tag").path("properties")).remove("key")),
                new Mutation("items 元素类型变化", Rule.REQUEST_FIELD_TYPE_CHANGED,
                        n -> ((ObjectNode) schema(n, "Tag").path("properties").path("weight"))
                                .put("type", "string")),
                new Mutation("items $ref 换组件", Rule.REQUEST_FIELD_TYPE_CHANGED,
                        n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties").path("tags"))
                                .set("items", JSON.createObjectNode()
                                        .put("$ref", "#/components/schemas/Template"))),
                new Mutation("items 元素新增必填", Rule.REQUEST_FIELD_NEWLY_REQUIRED,
                        n -> ((ObjectNode) schema(n, "Tag")).set("required",
                                JSON.createArrayNode().add("key"))),
                new Mutation("items 元素定义消失", Rule.REQUEST_FIELD_REMOVED,
                        n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties").path("tags"))
                                .remove("items")),
                // ---- additionalProperties（Map 值） ----
                new Mutation("Map 值类型变化", Rule.REQUEST_FIELD_TYPE_CHANGED,
                        n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties").path("meta")
                                .path("additionalProperties")).put("type", "integer")),
                new Mutation("Map 值定义消失", Rule.REQUEST_FIELD_REMOVED,
                        n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties").path("meta"))
                                .remove("additionalProperties")),
                // ---- 组合 schema ----
                new Mutation("组合成员内部约束收紧", Rule.CONSTRAINT_TIGHTENED,
                        n -> ((ObjectNode) schema(n, "Template").path("properties").path("group")
                                .path("anyOf").get(0)).put("maxLength", 5)),
                new Mutation("组合成员数量变化", Rule.RESPONSE_FIELD_TYPE_CHANGED,
                        n -> ((ArrayNode) schema(n, "Template").path("properties").path("group")
                                .path("anyOf")).add(JSON.createObjectNode().put("type", "boolean"))),
                // ---- 约束键 ----
                new Mutation("请求上界收紧（maxLength 变小）", Rule.CONSTRAINT_TIGHTENED,
                        n -> ((ObjectNode) schema(n, "Tag").path("properties").path("key"))
                                .put("maxLength", 4)),
                new Mutation("请求下界收紧（minimum 变大）", Rule.CONSTRAINT_TIGHTENED,
                        n -> ((ObjectNode) schema(n, "Tag").path("properties").path("weight"))
                                .put("minimum", 1)),
                new Mutation("新增强度约束（从无到有）", Rule.CONSTRAINT_TIGHTENED,
                        n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties").path("tag"))
                                .put("maxLength", 4)),
                new Mutation("items 上界收紧（maxItems 变小）", Rule.CONSTRAINT_TIGHTENED,
                        n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties").path("tags"))
                                .put("maxItems", 5)),
                new Mutation("pattern 新增", Rule.CONSTRAINT_TIGHTENED,
                        n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties").path("tag"))
                                .put("pattern", "^[a-z]+$")),
                new Mutation("响应约束收紧", Rule.CONSTRAINT_TIGHTENED,
                        n -> ((ObjectNode) schema(n, "Template").path("properties").path("name"))
                                .put("maxLength", 32)),
                // ---- 响应 ----
                new Mutation("删响应码", Rule.RESPONSE_REMOVED,
                        n -> ((ObjectNode) item(n).path("get").path("responses")).remove("404")),
                new Mutation("响应字段删除", Rule.RESPONSE_FIELD_REMOVED,
                        n -> ((ObjectNode) schema(n, "ResultTemplate").path("properties")).remove("data")),
                new Mutation("响应字段类型变化", Rule.RESPONSE_FIELD_TYPE_CHANGED,
                        n -> ((ObjectNode) schema(n, "ResultTemplate").path("properties")
                                .path("code")).put("type", "string")),
                new Mutation("响应嵌套字段（经 $ref）删除", Rule.RESPONSE_FIELD_REMOVED,
                        n -> ((ObjectNode) schema(n, "Template").path("properties")).remove("name")),
                new Mutation("响应枚举新增值（codegen 遇新值）", Rule.ENUM_EXTENDED,
                        n -> ((ArrayNode) schema(n, "Template").path("properties").path("status")
                                .path("enum")).add("ARCHIVED")),
                new Mutation("响应枚举声明整删", Rule.ENUM_REMOVED,
                        n -> ((ObjectNode) schema(n, "Template").path("properties").path("status"))
                                .remove("enum")));

        List<String> failures = new ArrayList<>();
        for (Mutation mutation : mutations) {
            JsonNode mutated = JSON.readTree(MINI_SPEC);
            mutation.mutation().accept(mutated);
            List<String> violations = OpenApiDriftComparator.diff(JSON.readTree(MINI_SPEC), mutated);
            boolean hit = violations.stream()
                    .anyMatch(v -> v.startsWith("[" + mutation.expected().name()));
            if (!hit) {
                failures.add(mutation.name() + " 未命中规则 " + mutation.expected().name()
                        + "，实际输出：" + violations);
            }
        }
        assertThat(failures).as("每条破坏性变异都必须被自己的规则抓住（共 "
                + mutations.size() + " 类）").isEmpty();
    }

    @Test
    @DisplayName("比较器自检（additive）：新增/放宽/镜像绿方向 17 类全部放行")
    void comparator_allowsAdditiveMutations() throws Exception {
        List<Consumer<JsonNode>> additives = List.of(
                // 新增可选参数
                n -> ((ArrayNode) item(n).path("get").path("parameters")).add(JSON.createObjectNode()
                        .put("name", "hint").put("in", "query").put("required", false)
                        .set("schema", JSON.createObjectNode().put("type", "string"))),
                // 新增 path
                n -> ((ObjectNode) n.path("paths")).set("/open/v1/codes",
                        JSON.createObjectNode().set("get", JSON.createObjectNode())),
                // 新增 method
                n -> ((ObjectNode) item(n)).set("delete", JSON.createObjectNode()),
                // 响应新增可选字段
                n -> ((ObjectNode) schema(n, "Template").path("properties"))
                        .set("memo", JSON.createObjectNode().put("type", "string")),
                // 请求枚举值增加（放宽：存量值仍全过）
                n -> ((ArrayNode) schema(n, "TemplateUpsert").path("properties").path("priority")
                        .path("enum")).add("mid"),
                // 新增响应码
                n -> ((ObjectNode) item(n).path("get").path("responses"))
                        .set("400", JSON.createObjectNode()),
                // 描述变化
                n -> ((ObjectNode) item(n).path("get")).put("summary", "换了文案"),
                // 响应侧 required 新增（服务端多保证一个字段不伤害客户端）
                n -> ((ObjectNode) schema(n, "Template")).set("required", JSON.createArrayNode().add("id")),
                // 请求体新增可选字段
                n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties"))
                        .set("note", JSON.createObjectNode().put("type", "string")),
                // 约束放宽（上界变大）
                n -> ((ObjectNode) schema(n, "Tag").path("properties").path("key")).put("maxLength", 16),
                // 约束删除（删掉 maxLength）
                n -> ((ObjectNode) schema(n, "Tag").path("properties").path("key")).remove("maxLength"),
                // items 上界放宽（maxItems 变大）
                n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties").path("tags"))
                        .put("maxItems", 20),
                // 请求枚举声明整删（放宽）
                n -> ((ObjectNode) schema(n, "TemplateUpsert").path("properties").path("priority"))
                        .remove("enum"),
                // 响应枚举值删除（服务端不再发它）
                n -> ((ArrayNode) schema(n, "Template").path("properties").path("status")
                        .path("enum")).remove(2),
                // 组合成员渲染顺序对调（规范化排序后同集，不构成变化）
                n -> ((ObjectNode) schema(n, "Template").path("properties").path("group"))
                        .set("anyOf", JSON.createArrayNode()
                                .add(JSON.createObjectNode().put("type", "integer"))
                                .add(JSON.createObjectNode().put("type", "string").put("maxLength", 10))),
                // items 元素新增可选字段
                n -> ((ObjectNode) schema(n, "Tag").path("properties"))
                        .set("label", JSON.createObjectNode().put("type", "string")),
                // 响应对象新增 Map 值声明（原本未声明，从无到有）
                n -> ((ObjectNode) schema(n, "Template")).set("additionalProperties",
                        JSON.createObjectNode().put("type", "string")));

        List<String> failures = new ArrayList<>();
        for (Consumer<JsonNode> additive : additives) {
            JsonNode mutated = JSON.readTree(MINI_SPEC);
            additive.accept(mutated);
            List<String> violations = OpenApiDriftComparator.diff(JSON.readTree(MINI_SPEC), mutated);
            if (!violations.isEmpty()) {
                failures.add("additive 变更被误判为破坏：" + violations);
            }
        }
        assertThat(failures).as("只增不改：additive 变更必须放行（共 " + additives.size() + " 类）").isEmpty();
    }

    // ------------------------------------------------------------------
    // 守卫 3：黄金样例——真实响应与 spec 契约一致
    // ------------------------------------------------------------------

    @Test
    @DisplayName("黄金样例：GET /open/v1/templates/{id} 真实响应的形状与 spec 一致（关键字段级）")
    void goldenSample_templateQuery_matchesSpec() throws Exception {
        OpenApiTestClient.Response r = client.get(
                "/open/v1/templates/" + templateId, null, APP_ID, secret);

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(r.body());
        JsonNode spec = fetchLiveSpec();

        // 信封三段：code/message/data 的 JSON 类型与 spec 顶层 schema 一致
        JsonNode envelope = deref(specResponseSchema(
                spec, "/open/v1/templates/{id}", "get", "200"), spec);
        assertThat(envelope.path("properties").path("code").path("type").asText())
                .as("spec 顶层必须有 code 字段描述").isEqualTo("integer");
        assertThat(body.path("code").isIntegralNumber()).as("响应 code 是整数").isTrue();
        assertThat(body.path("message").isTextual()).as("响应 message 是字符串").isTrue();
        assertThat(body.path("data").isObject()).as("响应 data 是对象").isTrue();
        assertThat(body.path("code").asInt()).isEqualTo(0);
        assertThat(body.path("data").path("id").asLong())
                .as("data.id 与真实模板 id 一致").isEqualTo(templateId);

        // data 内逐字段对拍：spec 声明的每个字段，只要响应里出现了非空值，JSON 类型就必须一致
        JsonNode dataSchema = deref(envelope.path("properties").path("data"), spec);
        JsonNode props = dataSchema.path("properties");
        assertThat(props.size())
                .as("spec 对 data 的字段描述不该是空的（空会让对拍假绿）").isGreaterThan(5);

        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (Iterator<String> it = props.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            JsonNode value = body.path("data").get(field);
            if (value == null || value.isNull()) {
                continue;   // 可空字段没出现：不算不一致（spec 未声明 required）
            }
            checked++;
            String schemaType = deref(props.get(field), spec).path("type").asText("");
            if (!schemaType.isEmpty() && !typeMatches(schemaType, value)) {
                mismatches.add(field + ": spec=" + schemaType + " 实际=" + value.getNodeType());
            }
        }
        assertThat(checked).as("至少对拍 8 个字段，防止循环空转假绿").isGreaterThanOrEqualTo(8);
        assertThat(mismatches).as("响应字段类型与 spec 不一致").isEmpty();
    }

    @Test
    @DisplayName("黄金样例（6010）：GET /open/v9/anything 无签名裸请求 → 400 + code=6010 + data.supportedVersions=[v1]")
    void unknownVersion_returns6010WithSupportedVersions() throws Exception {
        // 版本检查在验签之前：不需要也不应该构造签名头——任何头都拦在第一步
        HttpResponse<String> r = rawGet("/open/v9/anything", null);

        assertThat(r.statusCode()).isEqualTo(400);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.path("code").asInt()).isEqualTo(6010);
        JsonNode supported = body.path("data").path("supportedVersions");
        List<String> versions = new ArrayList<>();
        supported.forEach(v -> versions.add(v.asText()));
        assertThat(supported.isArray())
                .as("data.supportedVersions 引导接入方可用版本：" + r.body()).isTrue();
        assertThat(versions).containsExactly("v1");
    }

    // ------------------------------------------------------------------
    // 守卫 4：安全面与版本头
    // ------------------------------------------------------------------

    @Test
    @DisplayName("安全面：匿名 GET /api/api-docs/open-v1 → 401（spec 端点吃 /api 管理面认证，不裸奔）")
    void apiDocsEndpoint_requiresAdminToken() throws Exception {
        HttpResponse<String> anonymous = rawGet("/api/api-docs/open-v1", null);
        assertThat(anonymous.statusCode())
                .as("spec 描述的是对外契约，但拉取口属于管理面：匿名必须 401").isEqualTo(401);
        assertThat(anonymous.body()).contains("7002");

        HttpResponse<String> authorized = rawGet("/api/api-docs/open-v1", ADMIN_TOKEN);
        assertThat(authorized.statusCode()).as("带管理令牌能拉到 spec").isEqualTo(200);
        assertThat(JSON.readTree(authorized.body()).path("paths").size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("版本头：认证成功的开放面响应带 X-API-Version: v1")
    void authenticatedResponse_carriesXApiVersionHeader() throws Exception {
        OpenApiTestClient.Response r = client.get(
                "/open/v1/templates/" + templateId, null, APP_ID, secret);

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.header("X-API-Version"))
                .as("接入方需要知道'这次调用按哪个版本执行的'").isEqualTo("v1");
    }

    // ------------------------------------------------------------------
    // 基线再生成（手动开关，日常不跑）
    // ------------------------------------------------------------------

    @Test
    @EnabledIfSystemProperty(named = "openapi.baseline.update", matches = "true")
    @DisplayName("基线再生成：拉 live spec 归一化后重写 docs/open-api/openapi-v1.json（改契约=先红后绿）")
    void regenerateBaseline() throws Exception {
        JsonNode normalized = fetchLiveSpec();
        Files.createDirectories(BASELINE.getParent());
        Files.writeString(BASELINE, JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(normalized) + "\n", StandardCharsets.UTF_8);
        System.out.println("openapi 基线已再生成：" + BASELINE.toAbsolutePath()
                + "（paths=" + normalized.path("paths").size() + "）——请 git diff 审查后提交");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 拉基线文件；不存在时给出再生成命令的提示（这是新人最常撞到的红灯） */
    private JsonNode loadBaseline() throws Exception {
        assertThat(Files.exists(BASELINE))
                .as("基线 spec 不存在：先跑 mvn test -Dtest=OpenApiContractIntegrationTest "
                        + "-Dopenapi.baseline.update=true 生成并提交").isTrue();
        return JSON.readTree(Files.readString(BASELINE, StandardCharsets.UTF_8));
    }

    /** 拉管理面 spec 端点并归一化（剥 servers：它带本环境的 host:port，是环境不是契约） */
    private JsonNode fetchLiveSpec() throws Exception {
        HttpResponse<String> r = rawGet("/api/api-docs/open-v1", ADMIN_TOKEN);
        assertThat(r.statusCode()).as("拉 live spec 失败：" + r.body()).isEqualTo(200);
        return normalize(JSON.readTree(r.body()));
    }

    private JsonNode normalize(JsonNode spec) {
        JsonNode copy = spec.deepCopy();
        ((ObjectNode) copy).remove("servers");
        return copy;
    }

    /** 无签名裸 GET（token 可空）。显式带 Accept，避免内容协商挑中 XML（既有测试的同款教训） */
    private HttpResponse<String> rawGet(String path, String adminToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (adminToken != null) {
            builder.header("X-Admin-Token", adminToken);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 从 spec 里取某端点某响应码的 schema 节点 */
    private JsonNode specResponseSchema(JsonNode spec, String path, String method, String status) {
        return spec.path("paths").path(path).path(method).path("responses")
                .path(status).path("content").path("application/json").path("schema");
    }

    /** 解 $ref（内部指针，带环防护）；非 $ref 原样返回 */
    private JsonNode deref(JsonNode schema, JsonNode root) {
        return deref(schema, root, new HashSet<>());
    }

    private JsonNode deref(JsonNode schema, JsonNode root, Set<String> seen) {
        JsonNode ref = schema == null ? null : schema.get("$ref");
        if (ref == null || ref.isNull()) {
            return schema == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : schema;
        }
        String pointer = ref.asText();
        if (!pointer.startsWith("#/") || !seen.add(pointer)) {
            return schema;
        }
        return deref(root.at(pointer.substring(1)), root, seen);
    }

    /** spec 类型 ↔ JSON 实例类型的判定（未知类型不猜——返回 true 不做假断言，由字段数门槛兜底） */
    private boolean typeMatches(String schemaType, JsonNode node) {
        return switch (schemaType) {
            case "integer" -> node.isIntegralNumber();
            case "number" -> node.isNumber();
            case "string" -> node.isTextual();
            case "boolean" -> node.isBoolean();
            case "array" -> node.isArray();
            case "object" -> node.isObject();
            default -> true;
        };
    }
}
