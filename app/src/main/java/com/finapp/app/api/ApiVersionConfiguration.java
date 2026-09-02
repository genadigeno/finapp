package com.finapp.app.api;

import com.finapp.platform.api.ApiVersion;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts every route this platform publishes under {@link ApiVersion#CURRENT_PREFIX}.
 *
 * <p><strong>Applied once, here, rather than written on each controller.</strong> A prefix
 * repeated in every {@code @RequestMapping} is a prefix somebody eventually omits, and an
 * unversioned endpoint is not a cosmetic slip: it is a route that can never be changed, because
 * there is no second version to move clients to. Centralising it means a controller cannot
 * express an opinion about versioning, which is the point — the version belongs to the surface,
 * not to a handler.
 *
 * <p>It also makes serving two versions at once a routing change rather than a rewrite: a second
 * configurer prefixing a {@code v2} package would do it, with no edit to any existing controller.
 *
 * <p><strong>Scoped to our own handlers by package.</strong> Framework-supplied controllers —
 * springdoc's {@code /v3/api-docs} in the test classpath today, anything similar later — are not
 * part of this platform's contract and must not be dragged under its version. Actuator is
 * unaffected for a different reason: its endpoints are served by their own handler mapping, which
 * {@link PathMatchConfigurer} does not touch, so health and readiness stay unversioned as
 * {@link ApiVersion} describes.
 */
@Configuration
class ApiVersionConfiguration implements WebMvcConfigurer {

    /**
     * Only handlers in this package tree are versioned.
     *
     * <p>Deliberately the root package rather than a list: an allow-list of packages is a list
     * somebody forgets to extend, and the failure mode is a silently unversioned endpoint.
     *
     * <p>Trailing dot on purpose. {@link HandlerTypePredicate} matches with {@code
     * Class.getName().startsWith(basePackage)}, so a bare {@code "com.finapp"} would also claim a
     * hypothetical {@code com.finappsomething}. Nothing loses the prefix by adding the dot, since
     * no production class sits directly in the root package - a module boundary rule forbids it.
     */
    static final String OUR_HANDLERS = "com.finapp.";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(
                ApiVersion.CURRENT_PREFIX, HandlerTypePredicate.forBasePackage(OUR_HANDLERS));
    }
}
