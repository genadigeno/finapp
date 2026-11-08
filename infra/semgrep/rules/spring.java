// The rules' own test: `semgrep --test` over this directory, offline, on every run of infra/scripts/sast.sh.
package plant;

import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class SpringRules {

    Object spel(SpelExpressionParser parser, String input) {
        // ruleid: finapp-spring-expression-from-input
        return parser.parseExpression(input).getValue();
    }

    Object spelConstant(SpelExpressionParser parser) {
        // ok: finapp-spring-expression-from-input
        return parser.parseExpression("1 + 1").getValue();
    }

    String redirect(String target) {
        // ruleid: finapp-spring-open-redirect
        return "redirect:" + target;
    }

    String redirectConstant() {
        // ok: finapp-spring-open-redirect
        return "redirect:/health";
    }

    // ruleid: finapp-spring-cors-wildcard
    @CrossOrigin
    String anyOrigin() {
        return "";
    }

    // ruleid: finapp-spring-cors-wildcard
    @CrossOrigin(origins = "*")
    String anyOriginNamed() {
        return "";
    }

    // ok: finapp-spring-cors-wildcard
    @CrossOrigin(origins = "https://example.test")
    String oneOrigin() {
        return "";
    }

    void registry(CorsRegistry registry) {
        // ruleid: finapp-spring-cors-wildcard
        registry.addMapping("/**").allowedOrigins("*");
    }

    void authorization(Object http, Object auth) throws Exception {
        // ruleid: finapp-spring-everything-permitted
        auth.anyRequest().permitAll();
        // ruleid: finapp-spring-everything-permitted
        auth.requestMatchers("/**").permitAll();
        // ok: finapp-spring-everything-permitted
        auth.requestMatchers("/actuator/health").permitAll();
        // ruleid: finapp-spring-csrf-disabled
        http.csrf().disable();
    }
}
