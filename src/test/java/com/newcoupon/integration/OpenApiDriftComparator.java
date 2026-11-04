package com.newcoupon.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * OpenAPI 基线 vs live spec 的"只增不改"门禁比较器（V6 2B-2，ADR-0022；2026-11-04 按
 * 30-agent 对抗审查重做 schema 下钻）——手写、零第三方依赖。
 *
 * <p><b>为什么手写而不用 openapi-diff/Pact</b>（ADR-0022 的裁剪）：本刀的判据只有一条
 * ——"v1 冻结 = 只增不改"——把它写成**显式规则表**比引一个通用工具更可防守：每条规则
 * 独立命名、独立注释、可独立变异取证；外部消费方（openapi-diff CLI / Spectral lint /
 * 前端 openapi-typescript）进 CI 时再换工具（与 V6 计划 L208 的裁剪一致）。
 *
 * <p><b>口径对齐业界</b>：规则集是 openapi-diff 的 breaking 默认集的子集+镜像，判定方向与
 * Stripe（"non-breaking version" 只允许 additive）和 GitHub（"new optional fields and
 * endpoints are not breaking"）的公开兼容性声明一致。
 *
 * <p><b>规则总表（每条可独立变异取证，MINI_SPEC 自检覆盖全部）</b>：
 * <table>
 * <tr><th>规则</th><th>红（破坏）</th></tr>
 * <tr><td>PATH_REMOVED / OPERATION_REMOVED</td><td>删 path / 删 method——调用方写死的两样东西</td></tr>
 * <tr><td>PARAM_REMOVED / PARAM_TYPE_CHANGED</td><td>参数删除 / 类型变化（按 name+in 配对）</td></tr>
 * <tr><td>PARAM_NEWLY_REQUIRED / PARAM_ADDED_REQUIRED</td><td>参数可选变必填 / 新增必填参数</td></tr>
 * <tr><td>REQUEST_BODY_NEWLY_REQUIRED</td><td>请求体从无到有或变必填</td></tr>
 * <tr><td>REQUEST/RESPONSE_FIELD_REMOVED</td><td>字段删除（含 items 元素字段、additionalProperties 值 schema 消失、media type 消失）</td></tr>
 * <tr><td>REQUEST/RESPONSE_FIELD_TYPE_CHANGED</td><td>字段类型变化（$ref 换组件、items 元素类型、additionalProperties 值类型、组合成员集变化）</td></tr>
 * <tr><td>REQUEST_FIELD_NEWLY_REQUIRED</td><td>请求体 required 新增条目（含 items 元素内）</td></tr>
 * <tr><td>RESPONSE_REMOVED</td><td>删响应码</td></tr>
 * <tr><td>ENUM_NARROWED</td><td>请求侧枚举值被删（存量合法值开始被拒）</td></tr>
 * <tr><td>ENUM_ADDED_CONSTRAINT</td><td>请求侧裸值新增 enum 约束（同上，另一个方向）</td></tr>
 * <tr><td>ENUM_EXTENDED</td><td>响应侧枚举新增值（codegen 客户端 switch 遇未分支新值）</td></tr>
 * <tr><td>ENUM_REMOVED</td><td>响应侧 enum 声明整体消失（值域保证消失）</td></tr>
 * <tr><td>CONSTRAINT_TIGHTENED</td><td>校验约束收紧：maximum/maxItems/maxLength 变小、
 *     minimum/minItems/minLength 变大、新增任一上/下界、pattern 新增或变化（javax.validation
 *     注解的渲染——收紧即拒存量合法请求；放宽/删除=绿）</td></tr>
 * </table>
 *
 * <p><b>下钻覆盖</b>（审查发现的盲区已逐一补上）：properties 之外还下钻
 * {@code items}（数组元素——本仓库 7/55 个组件仅经 items 可达）、{@code additionalProperties}
 * 为对象时（Map 型值 schema）、组合 schema（allOf/anyOf/oneOf 成员按**规范化序列化串排序**
 * 后配对再递归——成员渲染顺序漂移不再假红）、pathItem 级共享 parameters、参数 schema 内部
 * （enum+约束键）。$ref 就地展开，环防护按递归路径。
 *
 * <p><b>已知不比（有意为之，不是遗漏）</b>：
 * <ul>
 *   <li>format 精度差异（int64→int32）：业界通例只把 type 语义变化计 breaking；</li>
 *   <li>additionalProperties 为 boolean（true/false）时的变化：Map 开关语义弱，登记不判；</li>
 *   <li>响应侧 required 新增：服务端多保证一个字段不伤害客户端（openapi-diff 同口径）；</li>
 *   <li>请求侧 enum 声明整体删除 / 枚举值增加 / 约束放宽或删除：都是放松，存量请求只会更好过；</li>
 *   <li>响应侧枚举值删除：服务端不再发它，客户端按"忽略未知"已能容错——镜像的破坏方向是"新增"；</li>
 *   <li>请求体整体删除 requestBody 声明：服务端不再约束 body（等价忽略未知字段的兼容方向）；</li>
 *   <li>description/summary/example/default 等文档性字段、servers/info：环境或注释，不是契约；</li>
 *   <li>exclusiveMaximum/exclusiveMinimum：随 maximum/minimum 主值变化已覆盖其语义主体。</li>
 * </ul>
 */
final class OpenApiDriftComparator {

    /** 破坏性规则集：每条对应"接入方存量代码会当场崩坏"的一类契约变化 */
    enum Rule {
        PATH_REMOVED("path 被删除"),
        OPERATION_REMOVED("path 内 method 被删除"),
        PARAM_REMOVED("请求参数被删除"),
        PARAM_TYPE_CHANGED("请求参数类型变化"),
        PARAM_NEWLY_REQUIRED("请求参数从可选变必填"),
        PARAM_ADDED_REQUIRED("新增必填请求参数"),
        REQUEST_BODY_NEWLY_REQUIRED("请求体从无到有或变为必填"),
        REQUEST_FIELD_REMOVED("请求体字段/元素被删除"),
        REQUEST_FIELD_TYPE_CHANGED("请求体字段/元素类型变化"),
        REQUEST_FIELD_NEWLY_REQUIRED("请求体新增必填字段"),
        RESPONSE_REMOVED("响应码被删除"),
        RESPONSE_FIELD_REMOVED("响应字段（或 media type/items/additionalProperties 值）被删除"),
        RESPONSE_FIELD_TYPE_CHANGED("响应字段类型变化"),
        ENUM_NARROWED("请求侧枚举值收窄（旧枚举值被移除）"),
        ENUM_ADDED_CONSTRAINT("请求侧新增枚举约束（存量合法值开始被拒）"),
        ENUM_EXTENDED("响应侧枚举新增值（codegen 客户端遇未分支新值）"),
        ENUM_REMOVED("响应侧枚举声明整体消失（值域保证消失）"),
        CONSTRAINT_TIGHTENED("校验约束收紧（maxLength/minimum 等变严或新增）");

        final String label;

        Rule(String label) {
            this.label = label;
        }
    }

    /** 比较发生在哪一侧：枚举与必填的破坏方向在请求/响应两侧互为镜像（见类注释） */
    private enum Side {
        REQUEST(true),
        RESPONSE(false);

        final boolean newlyRequiredIsBreaking;

        Side(boolean newlyRequiredIsBreaking) {
            this.newlyRequiredIsBreaking = newlyRequiredIsBreaking;
        }
    }

    /** OpenAPI 的 HTTP method 键（pathItem 里除它们之外的键是 description/parameters 等元数据） */
    private static final List<String> HTTP_METHODS =
            List.of("get", "post", "put", "delete", "patch", "head", "options", "trace");

    /** 上界类约束键：live 值变小、或从无到有 = 收紧（拒掉原本合法的大值/长值/多条目） */
    private static final List<String> UPPER_BOUND_KEYS = List.of("maximum", "maxItems", "maxLength");
    /** 下界类约束键：live 值变大、或从无到有 = 收紧（拒掉原本合法的小值/短值/空集合） */
    private static final List<String> LOWER_BOUND_KEYS = List.of("minimum", "minItems", "minLength");

    /** canonicalKey 的序列化器（成员排序配对用） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenApiDriftComparator() {
    }

    /**
     * 对比基线与 live spec，返回破坏性变更清单（空 = 只增不改，放行）。
     * 每条形如 {@code [REQUEST_FIELD_REMOVED·…] POST /open/v1/templates → application/json: name}。
     */
    static List<String> diff(JsonNode baseline, JsonNode live) {
        List<String> violations = new ArrayList<>();
        checkPaths(baseline, live, baseline, live, violations);
        return violations;
    }

    // ------------------------------------------------------------------
    // 规则：path 与 method
    // ------------------------------------------------------------------

    /** PATH_REMOVED / OPERATION_REMOVED：调用方的 URL 与 HTTP 动词是它写死的两样东西，删任何一个都是 404/405 */
    private static void checkPaths(JsonNode baseline, JsonNode live,
                                   JsonNode baseRoot, JsonNode liveRoot, List<String> violations) {
        JsonNode basePaths = baseline.path("paths");
        JsonNode livePaths = live.path("paths");
        for (Iterator<String> it = basePaths.fieldNames(); it.hasNext(); ) {
            String path = it.next();
            JsonNode liveItem = livePaths.get(path);
            if (liveItem == null) {
                violations.add(message(Rule.PATH_REMOVED, "", path));
                continue;
            }
            // pathItem 级共享参数（OpenAPI 允许 path 上声明 parameters 供全部方法继承）：
            // 与 operation 级参数同一套规则——只看 operation.parameters 会漏掉这一层
            checkParameters("PATH-LEVEL " + path,
                    basePaths.path(path).get("parameters"), liveItem.get("parameters"),
                    baseRoot, liveRoot, violations);
            for (String method : HTTP_METHODS) {
                if (basePaths.path(path).has(method) && !liveItem.has(method)) {
                    violations.add(message(Rule.OPERATION_REMOVED, method.toUpperCase(), path));
                }
            }
            for (String method : HTTP_METHODS) {
                if (basePaths.path(path).has(method) && liveItem.has(method)) {
                    checkOperation(method.toUpperCase() + " " + path,
                            basePaths.path(path).get(method), liveItem.get(method),
                            baseRoot, liveRoot, violations);
                }
            }
        }
        // live 里的新 path / 新 method：additive，允许——不产生 violation
    }

    private static void checkOperation(String where, JsonNode baseOp, JsonNode liveOp,
                                       JsonNode baseRoot, JsonNode liveRoot, List<String> violations) {
        checkParameters(where, baseOp.get("parameters"), liveOp.get("parameters"),
                baseRoot, liveRoot, violations);
        checkRequestBody(where, baseOp.get("requestBody"), liveOp.get("requestBody"),
                baseRoot, liveRoot, violations);
        checkResponses(where, baseOp.get("responses"), liveOp.get("responses"),
                baseRoot, liveRoot, violations);
    }

    // ------------------------------------------------------------------
    // 规则：请求参数（按 name+in 配对——OpenAPI 里这两个字段合起来才是参数的身份）
    // ------------------------------------------------------------------

    private static void checkParameters(String where, JsonNode baseParams, JsonNode liveParams,
                                        JsonNode baseRoot, JsonNode liveRoot, List<String> violations) {
        if (baseParams == null || !baseParams.isArray()) {
            baseParams = EMPTY_ARRAY;
        }
        if (liveParams == null || !liveParams.isArray()) {
            liveParams = EMPTY_ARRAY;
        }
        // 基线参数在 live 侧的命运：删除 / 变必填 / 类型变化 / schema 内部（enum、约束、items）
        for (JsonNode baseParam : baseParams) {
            JsonNode liveParam = findParam(liveParams, baseParam.path("name").asText(),
                    baseParam.path("in").asText());
            if (liveParam == null) {
                violations.add(message(Rule.PARAM_REMOVED, where, paramIdentity(baseParam)));
                continue;
            }
            if (!baseParam.path("required").asBoolean(false)
                    && liveParam.path("required").asBoolean(false)) {
                violations.add(message(Rule.PARAM_NEWLY_REQUIRED, where, paramIdentity(baseParam)));
            }
            String baseType = shapeToken(baseParam.path("schema"));
            String liveType = shapeToken(liveParam.path("schema"));
            if (!baseType.equals(liveType)) {
                violations.add(message(Rule.PARAM_TYPE_CHANGED, where,
                        paramIdentity(baseParam) + ": " + baseType + " → " + liveType));
                continue;
            }
            // 参数 schema 下钻：query/header 参数里的 enum、约束键同样受保护
            //（checkSchema 对无 properties/items/enum/约束的纯标量是 no-op，不会误报）
            checkSchema(where + " " + paramIdentity(baseParam),
                    baseParam.path("schema"), liveParam.path("schema"),
                    baseRoot, liveRoot, Side.REQUEST, Set.of(), violations);
        }
        // live 独有的参数：可选新增允许（additive），必填新增是破坏（存量请求没带它）
        for (JsonNode liveParam : liveParams) {
            JsonNode baseParam = findParam(baseParams, liveParam.path("name").asText(),
                    liveParam.path("in").asText());
            if (baseParam == null && liveParam.path("required").asBoolean(false)) {
                violations.add(message(Rule.PARAM_ADDED_REQUIRED, where, paramIdentity(liveParam)));
            }
        }
    }

    // ------------------------------------------------------------------
    // 规则：请求体
    // ------------------------------------------------------------------

    private static void checkRequestBody(String where, JsonNode baseBody, JsonNode liveBody,
                                         JsonNode baseRoot, JsonNode liveRoot, List<String> violations) {
        boolean baseRequired = baseBody != null && baseBody.path("required").asBoolean(false);
        boolean liveRequired = liveBody != null && liveBody.path("required").asBoolean(false);
        if (!baseRequired && liveRequired) {
            // 无请求体 → 有请求体，或可选 → 必填：两条路都是"存量请求开始被拒"
            violations.add(message(Rule.REQUEST_BODY_NEWLY_REQUIRED, where, "requestBody.required"));
        }
        if (baseBody == null || liveBody == null) {
            // live 完全删掉请求体：存量客户端还在发 body——服务端不再读它一般是放松，
            // 按"忽略未知字段"的兼容方向处理，不判红（类注释的已知不比清单）
            return;
        }
        checkContent(where, baseBody.path("content"), liveBody.path("content"),
                baseRoot, liveRoot, Side.REQUEST, Rule.REQUEST_FIELD_REMOVED, violations);
    }

    // ------------------------------------------------------------------
    // 规则：响应
    // ------------------------------------------------------------------

    private static void checkResponses(String where, JsonNode baseResponses, JsonNode liveResponses,
                                       JsonNode baseRoot, JsonNode liveRoot, List<String> violations) {
        if (baseResponses == null) {
            return;
        }
        for (Iterator<String> it = baseResponses.fieldNames(); it.hasNext(); ) {
            String status = it.next();
            JsonNode liveResp = liveResponses == null ? null : liveResponses.get(status);
            if (liveResp == null) {
                violations.add(message(Rule.RESPONSE_REMOVED, where, "responses." + status));
                continue;
            }
            checkContent(where + " → " + status, baseResponses.path(status).path("content"),
                    liveResp.path("content"), baseRoot, liveRoot, Side.RESPONSE,
                    Rule.RESPONSE_FIELD_REMOVED, violations);
        }
        // live 新增响应码：additive（客户端本来就要对未知 HTTP 状态容错），允许
    }

    /** media type 层：基线有的 media type live 必须还有（删除 = 该形态响应整体消失） */
    private static void checkContent(String where, JsonNode baseContent, JsonNode liveContent,
                                     JsonNode baseRoot, JsonNode liveRoot, Side side,
                                     Rule mediaTypeRemovedRule, List<String> violations) {
        if (baseContent == null) {
            return;
        }
        for (Iterator<String> it = baseContent.fieldNames(); it.hasNext(); ) {
            String mediaType = it.next();
            JsonNode liveMediaType = liveContent == null ? null : liveContent.get(mediaType);
            if (liveMediaType == null) {
                violations.add(message(mediaTypeRemovedRule, where, mediaType));
                continue;
            }
            checkSchema(where + " " + mediaType,
                    baseContent.path(mediaType).path("schema"), liveMediaType.path("schema"),
                    baseRoot, liveRoot, side, Set.of(), violations);
        }
    }

    // ------------------------------------------------------------------
    // 规则：schema 本体（字段/元素/Map 值/组合成员/枚举/约束/必填；$ref 就地展开）
    // ------------------------------------------------------------------

    private static void checkSchema(String where, JsonNode baseSchema, JsonNode liveSchema,
                                    JsonNode baseRoot, JsonNode liveRoot, Side side,
                                    Set<String> ancestors, List<String> violations) {
        // 当前节点若是 $ref，把它计入环防护集再展开——每条递归路径一个新集合，兄弟 $ref 互不挡路
        String baseRef = refOf(baseSchema);
        JsonNode base = deref(baseSchema, baseRoot, ancestors);
        JsonNode live = deref(liveSchema, liveRoot, ancestors);
        Rule removedRule = side == Side.REQUEST ? Rule.REQUEST_FIELD_REMOVED : Rule.RESPONSE_FIELD_REMOVED;
        Rule typeRule = side == Side.REQUEST
                ? Rule.REQUEST_FIELD_TYPE_CHANGED : Rule.RESPONSE_FIELD_TYPE_CHANGED;
        Set<String> nextAncestors = baseRef != null ? plus(ancestors, baseRef) : ancestors;

        checkConstraints(where, base, live, violations);
        checkEnum(where, base, live, side, violations);

        // required 新增条目：请求侧破坏（存量请求开始缺字段），响应侧允许（多保证 ≠ 少给）
        if (side.newlyRequiredIsBreaking) {
            Set<String> baseRequired = textSet(base.path("required"));
            for (String r : textSet(live.path("required"))) {
                if (!baseRequired.contains(r)) {
                    violations.add(message(Rule.REQUEST_FIELD_NEWLY_REQUIRED, where,
                            "required += " + r));
                }
            }
        }

        // items 下钻：数组元素是契约的一部分（列表接口的 data.coupons[*]、请求体的批量元素都住在这里）。
        // 基线没有 items 而 live 有 = 元素约束从无到有（additive 方向，元素原本就未声明），不判。
        JsonNode baseItems = base.get("items");
        JsonNode liveItems = live.get("items");
        if (baseItems != null && !baseItems.isNull()) {
            if (liveItems == null || liveItems.isNull()) {
                violations.add(message(removedRule, where, "items（数组元素定义消失）"));
            } else if (!shapeToken(baseItems).equals(shapeToken(liveItems))) {
                violations.add(message(typeRule, where, "items[]: "
                        + shapeToken(baseItems) + " → " + shapeToken(liveItems)));
            } else {
                checkSchema(where + "[]", baseItems, liveItems,
                        baseRoot, liveRoot, side, nextAncestors, violations);
            }
        }

        // additionalProperties 下钻（Map 型）：值为对象时是"Map 值的 schema"，按伪子 schema 比较；
        // 为 boolean 时不判（类注释的已知不比——Map 开关语义弱，双方都可能只是没写）
        JsonNode baseAddl = base.get("additionalProperties");
        JsonNode liveAddl = live.get("additionalProperties");
        if (baseAddl != null && baseAddl.isObject()) {
            if (liveAddl == null || !liveAddl.isObject()) {
                violations.add(message(removedRule, where, "additionalProperties（Map 值定义消失）"));
            } else if (!shapeToken(baseAddl).equals(shapeToken(liveAddl))) {
                violations.add(message(typeRule, where, "additionalProperties{*}: "
                        + shapeToken(baseAddl) + " → " + shapeToken(liveAddl)));
            } else {
                checkSchema(where + "{*}", baseAddl, liveAddl,
                        baseRoot, liveRoot, side, nextAncestors, violations);
            }
        }

        // 组合 schema（allOf/anyOf/oneOf）：成员按规范化序列化串**排序后配对**再逐对下钻。
        // shapeToken 对组合只看种类（"allOf"/"anyOf"/"oneOf"）——成员集或成员内容的变化
        // 由这里的配对逻辑产出精确规则，成员渲染顺序漂移不再构成假红。
        List<String> composedKinds = List.of("allOf", "anyOf", "oneOf");
        for (String kind : composedKinds) {
            JsonNode baseMembers = base.get(kind);
            if (baseMembers == null || !baseMembers.isArray()) {
                continue;
            }
            JsonNode liveMembers = live.get(kind);
            if (liveMembers == null || !liveMembers.isArray()) {
                violations.add(message(typeRule, where, kind + " 组合声明整体消失"));
                continue;
            }
            List<JsonNode> baseSorted = sortedByCanonicalKey(baseMembers);
            List<JsonNode> liveSorted = sortedByCanonicalKey(liveMembers);
            if (baseSorted.size() != liveSorted.size()) {
                violations.add(message(typeRule, where, kind + " 成员数量 "
                        + baseSorted.size() + " → " + liveSorted.size()));
                continue;
            }
            for (int i = 0; i < baseSorted.size(); i++) {
                // canonicalKey 相同的成员内容完全一致，无需下钻；不同的下钻产出精确规则
                if (!canonicalKey(baseSorted.get(i)).equals(canonicalKey(liveSorted.get(i)))) {
                    checkSchema(where + " " + kind + "[" + i + "]",
                            baseSorted.get(i), liveSorted.get(i),
                            baseRoot, liveRoot, side, nextAncestors, violations);
                }
            }
        }

        // 逐字段：删除 / 类型变化（shape 相同才继续深入比子字段）
        JsonNode baseProps = base.path("properties");
        JsonNode liveProps = live.path("properties");
        for (Iterator<String> it = baseProps.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            JsonNode liveProp = liveProps.get(field);
            if (liveProp == null) {
                violations.add(message(removedRule, where, field));
                continue;
            }
            String baseShape = shapeToken(baseProps.get(field));
            String liveShape = shapeToken(liveProp);
            if (!baseShape.equals(liveShape)) {
                violations.add(message(typeRule, where, field + ": " + baseShape + " → " + liveShape));
                continue;
            }
            checkSchema(where + "." + field, baseProps.get(field), liveProp,
                    baseRoot, liveRoot, side, nextAncestors, violations);
        }
        // live 新增字段：additive，允许——不产生 violation
    }

    // ------------------------------------------------------------------
    // 规则细节：校验约束（方向敏感）
    // ------------------------------------------------------------------

    /**
     * 约束键收紧判定（两侧同规则；实际触发源主要是请求侧的 javax.validation 注解渲染）：
     * 上界（maximum/maxItems/maxLength）值变小或从无到有 = 收紧；下界（minimum/minItems/minLength）
     * 值变大或从无到有 = 收紧；pattern 新增或变化 = 收紧（正则的"放宽"不可判定，从严）。
     * 反方向（放宽/删除）都是绿——存量请求的通过集只会变大。
     */
    private static void checkConstraints(String where, JsonNode base, JsonNode live,
                                         List<String> violations) {
        for (String key : UPPER_BOUND_KEYS) {
            if (!live.has(key) || live.get(key).isNull()) {
                continue;   // live 没有该约束 = 放宽/删除，绿
            }
            if (!base.has(key) || base.get(key).isNull()) {
                violations.add(message(Rule.CONSTRAINT_TIGHTENED, where, key + " 从无到有("
                        + live.get(key).asText() + ")"));
            } else if (Double.compare(live.get(key).asDouble(), base.get(key).asDouble()) < 0) {
                violations.add(message(Rule.CONSTRAINT_TIGHTENED, where,
                        key + " " + base.get(key).asText() + " → " + live.get(key).asText()));
            }
        }
        for (String key : LOWER_BOUND_KEYS) {
            if (!live.has(key) || live.get(key).isNull()) {
                continue;
            }
            if (!base.has(key) || base.get(key).isNull()) {
                violations.add(message(Rule.CONSTRAINT_TIGHTENED, where, key + " 从无到有("
                        + live.get(key).asText() + ")"));
            } else if (Double.compare(live.get(key).asDouble(), base.get(key).asDouble()) > 0) {
                violations.add(message(Rule.CONSTRAINT_TIGHTENED, where,
                        key + " " + base.get(key).asText() + " → " + live.get(key).asText()));
            }
        }
        JsonNode livePattern = live.get("pattern");
        if (livePattern != null && !livePattern.isNull()) {
            JsonNode basePattern = base.get("pattern");
            if (basePattern == null || basePattern.isNull()
                    || !basePattern.asText().equals(livePattern.asText())) {
                violations.add(message(Rule.CONSTRAINT_TIGHTENED, where,
                        "pattern " + (basePattern == null || basePattern.isNull()
                                ? "从无到有" : basePattern.asText() + " → " + livePattern.asText())));
            }
        }
    }

    // ------------------------------------------------------------------
    // 规则细节：枚举（请求/响应两侧镜像）
    // ------------------------------------------------------------------

    /**
     * 枚举镜像口径（openapi-diff 默认集的镜像方向）：
     * <ul>
     *   <li><b>请求侧</b>（服务端按 enum 校验入参）：值被删 = 存量合法值开始被拒 = 红（ENUM_NARROWED）；
     *       裸值新增 enum = 同上另一个方向 = 红（ENUM_ADDED_CONSTRAINT）；值增加 = 放宽 = 绿；
     *       enum 声明整删 = 放宽 = 绿；</li>
     *   <li><b>响应侧</b>（enum 是服务端对输出值域的保证，codegen 生成带分支的枚举类型）：
     *       值新增 = 客户端 switch 遇未分支新值 = 红（ENUM_EXTENDED）；enum 声明整删 = 值域保证
     *       消失 = 红（ENUM_REMOVED）；值删除 = 服务端不再发它 = 绿。</li>
     * </ul>
     */
    private static void checkEnum(String where, JsonNode base, JsonNode live, Side side,
                                  List<String> violations) {
        JsonNode baseEnum = base.get("enum");
        JsonNode liveEnum = live.get("enum");
        boolean baseHas = baseEnum != null && baseEnum.isArray();
        boolean liveHas = liveEnum != null && liveEnum.isArray();

        if (side == Side.REQUEST) {
            if (baseHas && liveHas) {
                for (JsonNode value : baseEnum) {
                    if (!containsValue(liveEnum, value)) {
                        violations.add(message(Rule.ENUM_NARROWED, where,
                                "enum 移除了 " + value.asText()));
                    }
                }
            } else if (!baseHas && liveHas) {
                violations.add(message(Rule.ENUM_ADDED_CONSTRAINT, where,
                        "enum 从无到有(" + textSet(liveEnum) + ")"));
            }
            // baseHas && !liveHas：enum 声明整删 = 放宽，绿
            return;
        }
        // RESPONSE：值域保证的破坏方向与请求侧相反
        if (!baseHas && liveHas) {
            return;   // 响应新增 enum 保证：服务端承诺收窄自己的输出，additive 方向，绿
        }
        if (baseHas && !liveHas) {
            violations.add(message(Rule.ENUM_REMOVED, where, "enum 声明整体消失"));
            return;
        }
        if (baseHas && liveHas) {
            for (JsonNode value : liveEnum) {
                if (!containsValue(baseEnum, value)) {
                    violations.add(message(Rule.ENUM_EXTENDED, where,
                            "enum 新增了 " + value.asText()));
                }
            }
            // 基线有 live 没有的值：服务端不再发它，绿
        }
    }

    // ------------------------------------------------------------------
    // 基础件
    // ------------------------------------------------------------------

    private static final JsonNode EMPTY_ARRAY =
            new com.fasterxml.jackson.databind.node.ArrayNode(
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);

    private static JsonNode findParam(JsonNode params, String name, String in) {
        for (JsonNode param : params) {
            if (param.path("name").asText().equals(name) && param.path("in").asText().equals(in)) {
                return param;
            }
        }
        return null;
    }

    private static String paramIdentity(JsonNode param) {
        return param.path("in").asText() + " 参数 " + param.path("name").asText();
    }

    /**
     * schema 的形状令牌：$ref 名（对外可见的组件名，重命名按类型变化从严对待）、type 名、
     * 或组合种类名（**只有种类，不含成员**——成员集/内容变化由 checkSchema 的排序配对分支
     * 产出精确规则，把成员拼进 token 会让"成员顺序漂移"和"成员内容微调"都变成笼统的
     * TYPE_CHANGED 假红/粗红）。缺失侧返回 &lt;缺失&gt;（一侧删了 schema 声明按类型变化处理）。
     */
    private static String shapeToken(JsonNode schema) {
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return "<缺失>";
        }
        String ref = refOf(schema);
        if (ref != null) {
            return ref;
        }
        JsonNode type = schema.get("type");
        if (type != null && !type.isNull()) {
            return type.asText();
        }
        for (String kind : List.of("allOf", "anyOf", "oneOf")) {
            if (schema.path(kind).isArray()) {
                return kind;
            }
        }
        return "<无 type>";
    }

    /** 成员的规范化键：整棵子树的序列化串。用于组合成员的稳定排序与"内容全等"判定 */
    private static String canonicalKey(JsonNode member) {
        try {
            return MAPPER.writeValueAsString(member);
        } catch (Exception e) {
            return member.toString();   // 序列化不会失败（纯 JsonNode），兜底防御
        }
    }

    /** 组合成员按规范化键排序：消除渲染顺序漂移，让两侧按内容配对 */
    private static List<JsonNode> sortedByCanonicalKey(JsonNode members) {
        List<JsonNode> list = new ArrayList<>();
        members.forEach(list::add);
        list.sort(Comparator.comparing(OpenApiDriftComparator::canonicalKey));
        return list;
    }

    private static boolean containsValue(JsonNode array, JsonNode value) {
        for (JsonNode item : array) {
            if (item.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String refOf(JsonNode schema) {
        JsonNode ref = schema == null ? null : schema.get("$ref");
        return ref == null || ref.isNull() ? null : ref.asText();
    }

    /** 解 $ref（仅内部指针 #/...；外链 ref 不展开、按名字原样比）。ancestors 含从根到当前的指针，防组件环 */
    private static JsonNode deref(JsonNode schema, JsonNode root, Set<String> ancestors) {
        String ref = refOf(schema);
        if (ref == null || !ref.startsWith("#/") || ancestors.contains(ref)) {
            return schema == null ? MissingNode.getInstance() : schema;
        }
        return deref(root.at(ref.substring(1)), root, plus(ancestors, ref));
    }

    private static Set<String> plus(Set<String> set, String value) {
        Set<String> next = new HashSet<>(set);
        next.add(value);
        return next;
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                values.add(item.asText());
            }
        }
        return values;
    }

    /** 统一的违规消息形态：[规则枚举名·label] METHOD path → 细节 */
    private static String message(Rule rule, String where, String detail) {
        return "[" + rule.name() + "·" + rule.label + "] " + where + " → " + detail;
    }
}
