package com.newcoupon.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 开关清单与**真实执行点**的一致性（S2 第 10 步的能力，补于第 11 步）。
 *
 * <p><b>为什么需要它</b>：开关清单上有一个"有没有接线"的标记，三级预案只收录标记为已接线的开关
 * （没接线的开关套进预案会"成功、有审计、配置也变了，但什么都没发生"）。
 * 那么"标记"与"现实"必须对得上——新写了一个 {@code @SwitchGuard} 却忘了改标记，
 * 预案就会漏掉这个开关，而**没有任何东西会提醒**。
 *
 * <p><b>扫描方式与它的边界</b>：直接读源码文本找 {@code @SwitchGuard(SwitchKey.X)}。
 * 这比反射扫 classpath 简单得多，代价是"只认源码里写死的字面量"——
 * 如果有人把开关写成变量（`@SwitchGuard(someVar)`，注解参数本来也不允许变量），
 * 这条测试看不到。**这个边界写在这里，免得后来者以为它覆盖了全部情况。**
 *
 * <p><b>它守的是单向的</b>：断言"用到的开关都标记为已接线"。
 * 反向（标了已接线但其实没接线）由预案的行为用例守——
 * 那种情况会表现为"套用后什么都没变"。
 */
class WiredSwitchKeysTest {

    private static final Pattern GUARD_USAGE =
            Pattern.compile("@SwitchGuard\\s*\\(\\s*SwitchKey\\.([A-Z_]+)\\s*\\)");

    @Test
    @DisplayName("所有被 @SwitchGuard 用到的开关，都必须标记为「已接线」")
    void everyGuardedSwitchIsMarkedWired() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        assertThat(Files.isDirectory(sourceRoot))
                .as("测试的工作目录应当是项目根（Maven 的默认行为）").isTrue();

        List<String> used = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = GUARD_USAGE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    used.add(m.group(1));
                }
            }
        }

        assertThat(used)
                .as("没扫到任何 @SwitchGuard，说明扫描本身坏了（而不是「没有开关」）")
                .isNotEmpty();

        for (String name : used) {
            SwitchKey key = SwitchKey.valueOf(name);
            assertThat(key.wired())
                    .as("开关 %s 在代码里被 @SwitchGuard 用着，却没标记为已接线——"
                            + "三级预案会漏掉它，而且没有任何东西会提醒。请把 SwitchKey 里 "
                            + "%s 的 wired 改成 true", name, name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("对照：未接线的开关确实存在（这条测试不是在空转）")
    void someSwitchesAreIntentionallyNotWired() {
        // 如果哪天所有开关都接线了，这个断言会失败——那时应该删掉它而不是改它，
        // 因为它的作用是证伪"上一条测试可能对任何输入都通过"
        assertThat(java.util.Arrays.stream(SwitchKey.values()).filter(k -> !k.wired()).count())
                .as("当前有登记了但还没接线的开关（后续阶段的功能）")
                .isPositive();
    }
}
