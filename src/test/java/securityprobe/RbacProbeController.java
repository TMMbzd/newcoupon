package securityprobe;

import com.newcoupon.common.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V6 2A 集成测试专用：方法安全探针（SecurityRbacIntegrationTest 通过 @Import 引入，
 * 只存在于该测试的 Spring 上下文）。
 *
 * <p><b>为什么放在 com.newcoupon 包之外</b>：主应用的组件扫描覆盖 com.newcoupon.**，
 * 而**测试类路径也在扫描范围内**——探针放在被扫包里会被所有集成测试上下文捡走
 * （注册两次直接 Ambiguous mapping 起不来；就算起来了也是把测试端点泄漏进别的上下文）。
 * 独立小包 + 显式 @Import = 哪个上下文要用就 import 进哪个，别处不可见。
 *
 * <p><b>为什么用 GET</b>：探针要穿过两道既有防线去单测方法安全——旧令牌过滤器只管
 * 写方法（GET 不设防）、CSRF 只管 /console/api/**。用 GET 后，@PreAuthorize 成为
 * 唯一变量：200 = 持有该权限，403 = 不持有。
 */
@RestController
public class RbacProbeController {

    @GetMapping("/api/rbac-probe/write")
    @PreAuthorize("hasAuthority('template:write')")
    public Result<String> writeProbe() {
        return Result.success("write-ok");
    }

    @GetMapping("/api/rbac-probe/approve")
    @PreAuthorize("hasAuthority('template:approve')")
    public Result<String> approveProbe() {
        return Result.success("approve-ok");
    }
}
