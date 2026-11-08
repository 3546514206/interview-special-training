package edu.zjnu.interview.ai.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 路径沙箱：把「模型能碰到的文件」限制在一个根目录内。
 *
 * <p><b>为什么这是 AI 场景下的高危点</b>：
 * 文件类工具的参数来自模型，而模型会读用户给的任何内容。
 * 一旦提示词注入让模型去读 {@code ~/.ssh/id_rsa} 或 {@code /etc/passwd}，
 * 就等于把宿主机的秘密通过工具结果回灌给了模型。
 * 所以「模型 → 工具」这条路径上，路径必须被当成<b>不可信输入</b>处理。
 *
 * <p><b>两道防线</b>：
 * <ol>
 *   <li><b>词法防穿越</b>：{@code ../} 与绝对路径。做法是
 *       {@code base.resolve(candidate).normalize()} 之后判断是否仍在 base 之下——
 *       必须先 resolve 再 normalize，否则 {@code a/../../etc} 这类组合会绕过。</li>
 *   <li><b>物理防逃逸</b>：符号链接。词法检查挡不住
 *       {@code root/link -> /etc} 这种「合法路径指向非法目录」。
 *       因此还要取目标路径<b>真实</b>位置（{@code toRealPath}）再判断一次。</li>
 * </ol>
 *
 * <p><b>失败即拒绝</b>：无法校验真实位置（例如链接悬空）时一律拒绝，
 * 绝不「校验不了就放行」——这是安全组件最重要的一条默认。
 */
public final class PathSandbox {

    /** 路径越界异常。 */
    public static final class SandboxViolation extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SandboxViolation(String message) {
            super(message);
        }
    }

    private final Path root;
    private final Path realRoot;

    private PathSandbox(Path root, Path realRoot) {
        this.root = root;
        this.realRoot = realRoot;
    }

    /**
     * 创建沙箱。
     *
     * @param root 沙箱根目录
     */
    public static PathSandbox of(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("沙箱根目录不能为空");
        }
        Path abs = root.toAbsolutePath().normalize();
        Path real = abs;
        try {
            real = abs.toRealPath();
        } catch (IOException e) {
            // 根目录还不存在：退化成绝对规范化路径（后续真正访问时仍会做 realPath 校验）
        }
        return new PathSandbox(abs, real);
    }

    /** 沙箱根目录（规范化后的绝对路径）。 */
    public Path root() {
        return root;
    }

    /** 沙箱根目录的真实位置（符号链接已解析）。 */
    public Path realRoot() {
        return realRoot;
    }

    /**
     * 解析候选路径到沙箱内部，越界则抛异常。
     *
     * @param root     沙箱根
     * @param candidate 候选路径（相对沙箱根，或绝对路径）
     * @return 沙箱内的绝对路径
     * @throws SandboxViolation 越界 / 符号链接逃逸 / 参数非法
     */
    public static Path resolveInside(Path root, String candidate) {
        if (root == null) {
            throw new SandboxViolation("未配置沙箱根目录");
        }
        if (candidate == null || candidate.isBlank()) {
            throw new SandboxViolation("路径参数为空");
        }
        Path base;
        try {
            base = root.toRealPath();
        } catch (IOException e) {
            base = root.toAbsolutePath().normalize();
        }

        // 第一道防线：词法。resolve 之后再 normalize，才能识别 a/../../ 这类组合穿越。
        Path target;
        try {
            target = base.resolve(candidate).normalize();
        } catch (RuntimeException e) {
            throw new SandboxViolation("非法路径: " + candidate);
        }
        if (!target.startsWith(base)) {
            throw new SandboxViolation("路径越界（拒绝）: " + candidate);
        }

        // 第二道防线：物理。找到最深的「已存在」祖先，解析其真实位置再判断一次。
        Path probe = target;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        if (probe != null) {
            Path real;
            try {
                real = probe.toRealPath();
            } catch (IOException e) {
                throw new SandboxViolation("无法校验路径真实位置（拒绝）: " + candidate);
            }
            if (!real.startsWith(base)) {
                throw new SandboxViolation("符号链接逃逸（拒绝）: " + candidate);
            }
        }
        return target;
    }

    /** 解析候选路径（使用本沙箱的根目录）。 */
    public Path resolve(String candidate) {
        return resolveInside(root, candidate);
    }

    /** 解析候选路径；越界返回空（不抛异常）。 */
    public Optional<Path> tryResolve(String candidate) {
        try {
            return Optional.of(resolve(candidate));
        } catch (SandboxViolation e) {
            return Optional.empty();
        }
    }

    /** 判断某个路径是否落在沙箱内（词法判断，不做符号链接校验）。 */
    public boolean contains(Path candidate) {
        if (candidate == null) {
            return false;
        }
        Path abs = candidate.isAbsolute() ? candidate.normalize() : root.resolve(candidate).normalize();
        return abs.startsWith(root);
    }

    /** 判断某个路径字符串是否落在沙箱内（含符号链接校验）。 */
    public boolean containsInside(String candidate) {
        return tryResolve(candidate).isPresent();
    }

    /** 从字符串解析路径（工具参数里拿到的往往是字符串）。 */
    public static Path parse(String candidate) {
        return Paths.get(candidate == null ? "" : candidate);
    }
}
