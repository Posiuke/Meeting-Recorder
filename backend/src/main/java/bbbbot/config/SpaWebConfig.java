package bbbbot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Liefert das gebaute React-Frontend (unter classpath:/static/) mit aus und
 * sorgt fuer den SPA-Fallback: Pfade, die keiner statischen Datei und keiner
 * API-Route entsprechen (z.B. Deep-Links wie /bots oder /recordings/42), werden
 * auf index.html geleitet, damit der React-Router (BrowserRouter) uebernehmen kann.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    private static final ClassPathResource INDEX = new ClassPathResource("/static/index.html");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // API- und Actuator-Pfade nicht auf die SPA umbiegen -
                        // die sollen bei fehlender Route ein echtes 404 liefern.
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
                            return null;
                        }
                        return INDEX;
                    }
                });
    }
}
