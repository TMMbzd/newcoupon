package com.newcoupon.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * OpenAPI 基线 vs live spec 的"只增不改"门禁比较器（V6 2B-2，ADR-0022）——手写、零第三方依赖。
 *
 * <p><b>为什么手写而不用 openapi-diff/Pact</b>（ADR-0022 的裁剪）：本刀的判据只有一条
 * ——"v1 冻结 = 只增不改"——把它写成**显式规则表**比引一个通用工具更可防守：每条规则
 * 独立命名、独立注释、可独立变异取证；外部消费方（openapi-diff CLI / Spectral lint /
 * 前端 openapi-typescript）进 CI 时再换工具（与 V6 计划 L208 的裁剪一致）。
 *
 * <p><b>口径对齐业界</b>：规则集是 openapi-diff 的 breaking 默认集的子集，判定方向与
 * Stripe（"non-breaking version" 只允许 additive）和 GitHub（"new optional fields and
 * endpoints are not breaking"）的公开兼容性声明一致：
 * <ul>
 *   <li><b>破坏</b>：删 path / 删 method / 删参数 / 参数类型变化 / 参数可选变必填 /
 *       新增必填参数 / 请求体字段删除·类型变化·新增必填 / 响应码删除 / 响应字段删除·类型变化 /
 *       枚举值收窄（旧枚举值被移除——客户端 switch 到它就是未定义行为）；</li>
 *   <li><b>允许（additive）</b>：新增 path / 新增 method / 新增可选参数与可选字段 /
 *       新增枚举值 / 新增响应码 / 描述（description/summary）变化 /
 *       <b>响应侧 required 新增</b>——服务端多保证一个字段不伤害已按"有就读"写的客户端，
 *       openapi-diff 同样不把响应 required 新增计为 breaking；请求侧新增必填则是破坏
 *       （客户端没带这个字段的存量请求会开始失败）。</li>
 * </ul>
 *
 * <p><b>故意不做深度 equals</b>：逐字节深度比对会把"新增"也判红，违背"只增不改"的承诺——
 * 那等于宣布 v1 一个字段都不能加，与 ADR-0016 的兼容承诺相反。
 *
 * <p><b>format 变化不判红</b>（如 int64→int32）：业界通例只把 type 语义变化计为 breaking，
 * format 精度差异由基线评审把关，不由门禁把关。
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
        REQUEST_FIELD_REMOVED("请求体字段被删除"),
        REQUEST_FIELD_TYPE_CHANGED("请求体字段类型变化"),
        REQUEST_FIELD_NEWLY_REQUIRED("请求体新增必填字段"),
        RESPONSE_REMOVED("响应码被删除"),
        RESPONSE_FIELD_REMOVED("响应字段（或 media type）被删除"),
        RESPONSE_FIELD_TYPE_CHANGED("响应字段类型变化"),
        ENUM_NARROWED("枚举值收窄（旧枚举值被移除）");

        final String label;

        Rule(String label) {
            this.label = label;
        }
    }

    /** 比较发生在哪一侧：请求侧的"新增必填"是破坏，响应侧不是（见类注释） */
    private enum Side {
        REQUEST(true),
        RESPONSE(false);

        final boolean newlyRequiredIsBreaking;

        Side(boolean newlyRequiredIsBreaking) {
            this.newlyRequiredIsBreaking = newlyRequiredIsBreaking;
        }
    }

    /** OpenAPI 的 HTTP method 键（pathItem 里除它们之外的键是 description/parameters 等元数据，不参与删除判定） */
    private static final List<String> HTTP_METHODS =
            List.of("get", "post", "put", "delete", "patch", "head", "options", "trace");

    private OpenApiDriftComparator() {
    }

    /**
     * 对比基线与 live spec，返回破坏性变更清单（空 = 只增不改，放行）。
     * 每条形如 {@code [REQUEST_FIELD_REMOVED] POST /open/v1/templates → application/json: name}。
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
        checkParameters(where, baseOp.get("parameters"), liveOp.get("parameters"), violations);
        checkRequestBody(where, baseOp.get("requestBody"), liveOp.get("requestBody"),
                baseRoot, liveRoot, violations);
        checkResponses(where, baseOp.get("responses"), liveOp.get("responses"),
                baseRoot, liveRoot, violations);
    }

    // ------------------------------------------------------------------
    // 规则：请求参数（按 name+in 配对——OpenAPI 里这两个字段合起来才是参数的身份）
    // ------------------------------------------------------------------

    private static void checkParameters(String where, JsonNode baseParams, JsonNode liveParams,
                                        List<String> violations) {
        if (baseParams == null || !baseParams.isArray()) {
            baseParams = MAPPER_EMPTY_ARRAY;
        }
        if (liveParams == null || !liveParams.isArray()) {
            liveParams = MAPPER_EMPTY_ARRAY;
        }
        // 基线参数在 live 侧的命运：删除 / 变必填 / 类型变化
        for (JsonNode baseParam : baseParams) {
            JsonNode liveParam = findParam(liveParams, baseParam.path("name").asText(),
                    baseParam.path("in").asText());
            if (liveParam == null) {
                violations.add(message(Rule.PARAM_REMOVED, where,
                        paramIdentity(baseParam)));
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
            }
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
            return;   // 任一侧没有 requestBody：剩下的情况都是放松（live 没有 = 删了可选招募？见下）
        }
        // live 完全删掉请求体：存量客户端还在发 body——服务端不再读它一般是放松，
        // 但"能发"变成"不能发"（415）取决于实现，从严按破坏登记在响应字段规则外单独说明：
        // 这里选择**不判红**（删除请求体定义 = 服务端不再约束 body，等价于"忽略未知字段"的兼容方向）。
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
    // 规则：schema（字段删除 / 类型变化 / 新增必填 / 枚举收窄；$ref 就地展开）
    // ------------------------------------------------------------------

    private static void checkSchema(String where, JsonNode baseSchema, JsonNode liveSchema,
                                    JsonNode baseRoot, JsonNode liveRoot, Side side,
                                    Set<String> ancestors, List<String> violations) {
        // 当前节点若是 $ref，把它计入环防护集再展开——每条递归路径一个新集合，兄弟 $ref 互不挡路
        String baseRef = refOf(baseSchema);
        JsonNode base = deref(baseSchema, baseRoot, ancestors);
        JsonNode live = deref(liveSchema, liveRoot, ancestors);

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

        // 枚举收窄：基线的每个枚举值必须仍在 live 里（客户端的 switch 分支不能变死代码）
        JsonNode baseEnum = base.get("enum");
        JsonNode liveEnum = live.get("enum");
        if (baseEnum != null && liveEnum != null) {
            for (JsonNode value : baseEnum) {
                boolean survived = false;
                for (JsonNode liveValue : liveEnum) {
                    if (liveValue.equals(value)) {
                        survived = true;
                        break;
                    }
                }
                if (!survived) {
                    violations.add(message(Rule.ENUM_NARROWED, where,
                            "enum 移除了 " + value.asText()));
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
                violations.add(message(side == Side.REQUEST
                        ? Rule.REQUEST_FIELD_REMOVED : Rule.RESPONSE_FIELD_REMOVED,
                        where, field));
                continue;
            }
            String baseShape = shapeToken(baseProps.get(field));
            String liveShape = shapeToken(liveProp);
            if (!baseShape.equals(liveShape)) {
                violations.add(message(side == Side.REQUEST
                        ? Rule.REQUEST_FIELD_TYPE_CHANGED : Rule.RESPONSE_FIELD_TYPE_CHANGED,
                        where, field + ": " + baseShape + " → " + liveShape));
                continue;
            }
            Set<String> next = baseRef != null ? plus(ancestors, baseRef) : ancestors;
            checkSchema(where + "." + field, baseProps.get(field), liveProp,
                    baseRoot, liveRoot, side, next, violations);
        }
        // live 新增字段：additive，允许——不产生 violation
    }

    /** 占位空数组：参数列表缺省时统一走同一循环 */
    private static final JsonNode MAPPER_EMPTY_ARRAY =
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

    /** schema 的形状令牌：$ref 名（对外可见的组件名，重命名按类型变化从严对待）或 type 名 */
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
        if (schema.has("anyOf") || schema.has("oneOf") || schema.has("allOf")) {
            // 组合 schema：拼子形状的有序串（两侧生成顺序一致——同一个 springdoc 生成器）
            StringBuilder sb = new StringBuilder("composed[");
            for (String key : List.of("allOf", "anyOf", "oneOf")) {
                for (JsonNode child : schema.path(key)) {
                    sb.append(shapeToken(child)).append(',');
                }
            }
            return sb.append(']').toString();
        }
        return "<无 type>";
    }

    private static String refOf(JsonNode schema) {
        JsonNode ref = schema == null ? null : schema.get("$ref");
        return ref == null || ref.isNull() ? null : ref.asText();
    }

    /** 解 $ref（仅内部指针 #/...；外链 ref 不展开、按名字原样比）。ancestors 含从根到当前的指针，防组件环 */
    private static JsonNode deref(JsonNode schema, JsonNode root, Set<String> ancestors) {
        String ref = refOf(schema);
        if (ref == null || !ref.startsWith("#/") || ancestors.contains(ref)) {
            return schema;
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

    /** 供报告展示的规则清单（人读的变更清单会引用这些名字） */
    static Map<String, String> ruleCatalog() {
        Map<String, String> catalog = new TreeMap<>();
        for (Rule rule : Rule.values()) {
            catalog.put(rule.name(), rule.label);
        }
        return catalog;
    }
}
