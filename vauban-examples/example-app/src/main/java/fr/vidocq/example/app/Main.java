package fr.vidocq.example.app;

import fr.vidocq.vauban.core.container.VaubanContainer;
import fr.vidocq.vauban.sjar.SjarKeyProvider;
import fr.vidocq.vauban.sjar.SjarPlugin;

import java.nio.file.Path;

/**
 * Entry point for the Vauban CDI example application.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Plain beans from example-lib loaded via {@code scanClasspath()}</li>
 *   <li>Encrypted beans from example-lib-securized loaded via {@code scanSjar()}</li>
 *   <li>Cross-library injection — WelcomeService uses beans from both sources</li>
 * </ul>
 *
 * <p>Usage: {@code java -jar example-app.jar [name] [path/to/securized.sjar]}
 */
@SuppressWarnings("java:S106") // Example app uses System.out intentionally
public class Main {

    public static void main(String[] args) {
        var name = args.length > 0 ? args[0] : "Vauban";
        var sjarPath = args.length > 1 ? args[1] : null;

        var builder = VaubanContainer.builder()
                .scanClasspath();

        // Load encrypted beans if SJAR path is provided
        if (sjarPath != null) {
            builder.addByteSourcePlugin(new SjarPlugin())
                   .pluginContext(new SjarKeyProvider())
                   .scanSjar(Path.of(sjarPath));
        }

        try (var container = builder.build()) {
            var welcomeService = container.select(WelcomeService.class);
            System.out.println(welcomeService.welcome(name));

            if (sjarPath != null) {
                System.out.println("Encoded: " + welcomeService.welcomeEncoded(name));
            }
        }
    }
}
