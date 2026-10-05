package dev.junxiex.mikukits;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * 声明运行期前置：sqlite-jdbc 由 Paper 在启动时从 Maven 仓库解析。
 * 若本地缓存（libraries/）已存在则直接复用，否则自动下载，插件 JAR 不再内置驱动。
 * <p>
 * 仓库顺序：papermc 镜像（代理 Maven Central，规避 Central 直连限速/ToS 警告）→ Central 官方镜像。
 */
@SuppressWarnings("unused") // 由服务端在类加载阶段实例化
public final class MikuKitsLoader implements PluginLoader {

    /** 与 MikuKitsPlugin 中显式加载驱动时使用的类（org.sqlite.JDBC）需来自同一版本。 */
    private static final String SQLITE_VERSION = "3.53.2.1";

    @Override
    public void classloader(PluginClasspathBuilder builder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addDependency(new Dependency(
                new DefaultArtifact("org.xerial:sqlite-jdbc:" + SQLITE_VERSION), null));
        resolver.addRepository(new RemoteRepository.Builder(
                "papermc", "default", "https://repo.papermc.io/repository/maven-public/").build());
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        builder.addLibrary(resolver);
    }
}
