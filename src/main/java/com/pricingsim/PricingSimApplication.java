package com.pricingsim;

import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the pricing &amp; promotion simulator.
 *
 * <ul>
 *   <li>No arguments: starts the REST API (web server). Flyway migrates the database on startup.</li>
 *   <li>Arguments: CLI mode without a web server. The DB-free commands ({@code generate}, {@code repro-check})
 *       also activate the {@code offline} profile, which excludes DataSource/JDBC/Flyway auto-configuration.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PricingSimApplication {

    /** CLI commands that must run without a database (PLAN.md §2 trade-offs, §3). */
    static final Set<String> OFFLINE_COMMANDS = Set.of("generate", "repro-check");

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PricingSimApplication.class);
        boolean cliMode = isCliMode(args);
        if (cliMode) {
            app.setWebApplicationType(WebApplicationType.NONE);
            if (OFFLINE_COMMANDS.contains(args[0])) {
                app.setAdditionalProfiles("offline");
            }
        }
        var context = app.run(args);
        if (cliMode) {
            // Propagate the command's exit code (ExitCodeGenerator beans) to the shell.
            System.exit(SpringApplication.exit(context));
        }
    }

    /** CLI mode when the first argument is a command, not a Spring option such as {@code --server.port=9090}. */
    public static boolean isCliMode(String[] args) {
        return args.length > 0 && !args[0].startsWith("--");
    }
}
