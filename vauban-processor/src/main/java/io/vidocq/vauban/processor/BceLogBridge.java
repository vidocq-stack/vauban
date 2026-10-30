/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.vauban.processor;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * While Vauban runs the Build Compatible Extensions at build time, what they log through {@code System.Logger} or
 * java.util.logging becomes a javac message, one line in the build's output: INFO a note, WARNING and SEVERE a
 * warning, the finer levels nothing. Without it, the root logger's console handler printed each line in two, the
 * date and the level in the machine's language, among Maven's {@code [INFO]} lines (BUG-20261030-26 of vidocq).
 * The parameters are formatted the same in every locale.
 */
final class BceLogBridge implements AutoCloseable {

    private final Logger root = LogManager.getLogManager().getLogger("");
    private final Handler[] previous;
    private final Handler bridge;

    private BceLogBridge(Messager messager, String prefix) {
        previous = root.getHandlers();
        bridge = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                Diagnostic.Kind kind = kindOf(logRecord.getLevel());
                if (kind != null) {
                    messager.printMessage(kind, prefix + message(logRecord));
                }
            }

            @Override
            public void flush() {
                // the messager writes at once
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        for (Handler handler : previous) {
            root.removeHandler(handler);
        }
        root.addHandler(bridge);
    }

    /** Routes the log records to {@code messager}, each prefixed with {@code prefix}, until {@link #close()}. */
    static BceLogBridge install(Messager messager, String prefix) {
        return new BceLogBridge(messager, prefix);
    }

    @Override
    public void close() {
        root.removeHandler(bridge);
        for (Handler handler : previous) {
            root.addHandler(handler);
        }
    }

    private static Diagnostic.Kind kindOf(Level level) {
        if (level.intValue() >= Level.WARNING.intValue()) {
            return Diagnostic.Kind.WARNING;
        }
        if (level.intValue() >= Level.INFO.intValue()) {
            return Diagnostic.Kind.NOTE;
        }
        return null;
    }

    private static String message(LogRecord logRecord) {
        String message = logRecord.getMessage();
        Object[] parameters = logRecord.getParameters();
        if (message == null) {
            return "";
        }
        if (parameters == null || parameters.length == 0) {
            return message;
        }
        try {
            return new MessageFormat(message, Locale.ROOT).format(parameters);
        } catch (IllegalArgumentException _) {
            return message;
        }
    }
}
