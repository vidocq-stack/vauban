package io.vidocq.vauban.example.app;

import io.vidocq.vauban.core.container.VaubanContainer;

/**
 * Entry point for the Vauban CDI example application.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Automatic bean discovery via {@code scanClasspath()} — reads
 *       {@code META-INF/vauban-beans.list} from all JARs and directories</li>
 *   <li>Plain beans from example-lib</li>
 *   <li>Encrypted beans from example-lib-securized (internal impls decrypted at runtime)</li>
 *   <li>Cross-library injection — WelcomeService uses both</li>
 * </ul>
 */
@SuppressWarnings("java:S106")
public class Main {

    public static void main(String[] args) {
        var name = args.length > 0 ? args[0] : "Vauban";

        try (var container = VaubanContainer.builder()
                .scanClasspath()
                .build()) {

            var welcome = container.select(WelcomeService.class);
            System.out.println(welcome.welcome(name));
            System.out.println("Encoded: " + welcome.welcomeEncoded(name));
        }
    }
}
