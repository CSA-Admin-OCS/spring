package com.open.spring.system;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    // set up your own index
    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        registry.addViewController("/login").setViewName("login");
    }

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }

    /* map path and location for "uploads" outside of application resources
       ... creates a directory outside "static" folder, "file:volumes/uploads"
       ... CRITICAL, without this uploaded file will not be loaded/displayed by frontend
     */
    @Override
    public void addResourceHandlers(final @NonNull ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/volumes/uploads/**").addResourceLocations("file:volumes/uploads/");
    }
    
    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns(
                "https://*.opencodingsociety.com",
                "https://open-coding-society.github.io",
                "http://127.0.0.1:4500",
                "http://127.0.0.1:4599",
                "http://127.0.0.1:4600",
                "http://127.0.0.1:8585",
                "http://localhost:4500",
                "http://localhost:4599",
                "http://localhost:4600",
                "http://localhost:8585"
            )
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowCredentials(true);
    }
    
}