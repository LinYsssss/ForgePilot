package com.example.codereview.language;

import com.example.codereview.language.java.JavaAnalysisPlugin;
import com.example.codereview.language.javascript.JavascriptAnalysisPlugin;
import com.example.codereview.language.python.PythonAnalysisPlugin;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LanguagePluginConfiguration {

    @Bean
    List<LanguagePlugin> languagePlugins(
            @Value("${app.sandbox.analysis-image:reposage-analysis@sha256:0000000000000000000000000000000000000000000000000000000000000000}")
            String imageDigest,
            @Value("${app.agent.language.source-version:language-rules-v1}") String sourceVersion
    ) {
        return List.of(
                new JavaAnalysisPlugin(imageDigest, sourceVersion),
                new PythonAnalysisPlugin(imageDigest, sourceVersion),
                new JavascriptAnalysisPlugin(imageDigest, sourceVersion)
        );
    }

    @Bean
    LanguagePluginSelector languagePluginSelector(List<LanguagePlugin> languagePlugins) {
        return new LanguagePluginSelector(languagePlugins);
    }
}
