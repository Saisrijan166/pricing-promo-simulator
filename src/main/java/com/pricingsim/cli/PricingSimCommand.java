package com.pricingsim.cli;

import org.springframework.stereotype.Component;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Top-level command (PLAN.md §3 cli/). Usage: {@code java -jar target/pricing-sim.jar <command> [options]}. Without a
 * command the jar starts the REST API instead (see {@code PricingSimApplication}).
 */
@Component
@Command(name = "pricing-sim",
        mixinStandardHelpOptions = true,
        version = "pricing-promo-simulator",
        description = "Pricing & promotion simulator: deterministic synthetic data generator and pipeline.",
        subcommands = {GenerateCommand.class, LoadCommand.class, ValidateCommand.class,
                DetectCommand.class, EvaluateCommand.class})
public class PricingSimCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
