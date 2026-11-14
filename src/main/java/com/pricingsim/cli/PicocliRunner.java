package com.pricingsim.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import com.pricingsim.PricingSimApplication;

import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Bridges Spring Boot and picocli (PLAN.md §3 cli/PicocliRunner). In CLI mode it runs the command and exposes its exit
 * code to {@code SpringApplication.exit}; in web mode (no command word) it does nothing. Subcommands are created by
 * the Spring-aware {@link IFactory}, so they can inject beans such as {@code GeneratorProperties}.
 */
@Component
public class PicocliRunner implements CommandLineRunner, ExitCodeGenerator {

    private final PricingSimCommand command;
    private final IFactory factory;
    private int exitCode;

    public PicocliRunner(PricingSimCommand command, IFactory factory) {
        this.command = command;
        this.factory = factory;
    }

    @Override
    public void run(String... args) {
        if (!PricingSimApplication.isCliMode(args)) {
            return;
        }
        CommandLine commandLine = new CommandLine(command, factory);
        commandLine.setExecutionExceptionHandler((ex, cl, parseResult) -> {
            cl.getErr().println("Error: " + ex.getMessage());
            return cl.getCommandSpec().exitCodeOnExecutionException();
        });
        exitCode = commandLine.execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
