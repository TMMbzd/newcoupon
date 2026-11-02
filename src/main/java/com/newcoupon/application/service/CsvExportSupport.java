package com.newcoupon.application.service;

/**
 * CSV 导出公共件（V6 阶段 1B 从 GrantTaskService.exportFailuresCsv 抽出，
 * 兑换码明文导出与发放失败明细导出共用同一套转义规则——两处各留一份迟早漂移）。
 *
 * <p>两条规则：
 * <ul>
 *   <li>BOM 字节前置：Excel 靠 BOM 识别 UTF-8，没有它双击打开中文必乱码；</li>
 *   <li>单元格先做公式注入防护再 RFC 4180 转义（顺序见 {@link #csvCell}）。</li>
 * </ul>
 */
final class CsvExportSupport {

    /** UTF-8 BOM 三字节：Excel 识别 UTF-8 的开关（有它中文不乱码）。 */
    static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** CSV 里会被表格软件当公式前缀的字符：= + - @ 与制表符。 */
    private static final String FORMULA_PREFIXES = "=+-@\t";

    private CsvExportSupport() {
    }

    /**
     * 单元格加工：先做公式注入防护（值以 = + - @ 或制表符开头时前置单引号），
     * 再做 RFC 4180 转义（含逗号/引号/换行时整格引号包裹、内部引号翻倍）。
     * 顺序固定"先防注入后转义"：注入防护加的引号本身不需要再转义，反过来则
     * 防护判断看到的是已被转义的值，判断依据就失真了。
     */
    static String csvCell(String raw) {
        String guarded = raw;
        if (!guarded.isEmpty() && FORMULA_PREFIXES.indexOf(guarded.charAt(0)) >= 0) {
            guarded = "'" + guarded;
        }
        if (guarded.indexOf(',') >= 0 || guarded.indexOf('"') >= 0
                || guarded.indexOf('\r') >= 0 || guarded.indexOf('\n') >= 0) {
            return '"' + guarded.replace("\"", "\"\"") + '"';
        }
        return guarded;
    }

    /**
     * 正文前拼接 BOM：BOM 必须在字节层面前置（它是三个字节 EF BB BF，不是字符拼接），
     * 直接拼字节让"BOM 恰在文件头"不言自明。
     */
    static byte[] withBom(String csvBody) {
        byte[] body = csvBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }
}
