package org.example.ykd_summer.runner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.Scanner;

@Component
public class CommandRunner implements ApplicationRunner {

    @Value("${app.version}")
    private String appVersion;

    @Value("${spring.application.name}")
    private String appName;

    @Override
    public void run(ApplicationArguments args) {
        Thread commandThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            printBanner();

            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine().trim();

                switch (input) {
                    case "help" -> printHelp();
                    case "version" -> printVersion();
                    case "status" -> printStatus();
                    default -> System.out.println("Unknown command. Type 'help' for available commands.");
                }
            }
        }, "command-thread");
        commandThread.setDaemon(true);
        commandThread.start();
    }

    private void printBanner() {
        System.out.println("=================================");
        System.out.println("  " + appName + " Command Console");
        System.out.println("  Type 'help' for available commands.");
        System.out.println("=================================");
    }

    private void printHelp() {
        System.out.println("Available commands:");
        System.out.println("  help    - Show this help message");
        System.out.println("  version - Show application version");
        System.out.println("  status  - Show application status");
    }

    private void printVersion() {
        System.out.println(appName + " v" + appVersion);
    }

    private void printStatus() {
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        long hours = uptime / 3600;
        long minutes = (uptime % 3600) / 60;
        long seconds = uptime % 60;

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);

        System.out.println("Application Status:");
        System.out.println("  Name    : " + appName);
        System.out.println("  Version : " + appVersion);
        System.out.println("  Uptime  : " + hours + "h " + minutes + "m " + seconds + "s");
        System.out.println("  Memory  : " + usedMemory + " MB used / " + totalMemory + " MB total");
    }
}
