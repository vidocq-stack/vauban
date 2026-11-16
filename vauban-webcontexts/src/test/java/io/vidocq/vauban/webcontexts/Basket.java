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
package io.vidocq.vauban.webcontexts;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.SessionScoped;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A session bean that can move to another container: {@code Serializable}, holding only serializable state. Its
 * lifecycle callbacks go to a static log, which both containers of a test share.
 */
@SessionScoped
public class Basket implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The lifecycle callbacks of every basket, in order. */
    static final List<String> LIFECYCLE = new CopyOnWriteArrayList<>();

    private final List<String> items = new ArrayList<>();

    public void add(String item) {
        items.add(item);
    }

    public List<String> items() {
        return List.copyOf(items);
    }

    @PostConstruct
    void created() {
        LIFECYCLE.add("post-construct");
    }

    @PreDestroy
    void destroyed() {
        LIFECYCLE.add("pre-destroy " + items);
    }
}
