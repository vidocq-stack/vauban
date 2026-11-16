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
package io.vidocq.vauban.core.container;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.inject.spi.PassivationCapable;
import jakarta.inject.Provider;
import java.io.InvalidObjectException;
import java.io.NotSerializableException;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * The serial form of a built-in {@code Event}, {@code Instance} or {@code Provider} the container injected into a
 * bean: the bean's passivation id and the injection point within that bean. The injected object writes it in its
 * place, so a session bean that holds one moves to another container (foy#114). It reads back as what the importing
 * container injects at the same injection point of the same bean: its own {@code Event}, which reaches its observers,
 * or its own {@code Instance}, which resolves its beans at {@code get()}. The read creates no bean instance.
 *
 * <p>Neither the selected type nor the qualifiers are written: the injection point gives them again. So an object
 * that no injection gave, one obtained by {@code select(...)} or by a programmatic lookup, cannot move.</p>
 *
 * @param passivationId the passivation id of the bean the injection point belongs to, its {@code BeanId}
 * @param member        the field, constructor or method, as {@link VaubanInjectionPoint#memberOf} names it: its
 *                      declaring class, its name and, for a constructor or a method, its erased parameter types
 * @param position      the position of the parameter in that constructor or method, {@code -1} for a field
 * @param type          the binary name of the type of the field or parameter: {@code Event}, {@code Instance} or
 *                      {@code Provider}
 */
record InjectionPointReference(String passivationId, String member, int position, String type)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The types a reference reads back as: the built-ins injected per injection point. */
    private static final Set<String> BUILT_INS =
            Set.of(Event.class.getName(), Instance.class.getName(), Provider.class.getName());

    /** How a message names the built-in that cannot move, which it cannot tell apart. */
    private static final String BUILT_IN = "an Event, an Instance or a Provider";

    /**
     * The reference to {@code injectionPoint}, where the container injected an {@code Event}, an {@code Instance} or
     * a {@code Provider}, or {@link NotSerializableException} naming why it cannot move: the web container then
     * leaves its session behind.
     */
    static InjectionPointReference to(InjectionPoint injectionPoint) throws NotSerializableException {
        if (injectionPoint == null) {
            // Only an Event reaches here: an observer method's Instance parameter is a programmatic lookup
            throw new NotSerializableException("an Event given with no injection point, as to an observer method,"
                    + " cannot move to another container: only one injected into a field, a constructor or an"
                    + " initializer method of a bean moves");
        }
        if (!(injectionPoint.getBean() instanceof PassivationCapable bean) || bean.getId() == null) {
            throw new NotSerializableException(BUILT_IN + " injected outside a bean with a passivation id cannot move"
                    + " to another container: " + injectionPoint);
        }
        String member = VaubanInjectionPoint.memberOf(injectionPoint);
        String type = VaubanInjectionPoint.typeOf(injectionPoint);
        if (member == null || type == null) {
            throw new NotSerializableException(BUILT_IN + " injected at " + injectionPoint
                    + " cannot move to another container: its injection point names no field and no parameter");
        }
        return new InjectionPointReference(bean.getId(), member, VaubanInjectionPoint.positionOf(injectionPoint),
                type);
    }

    /**
     * What the importing container injects at the same injection point of the same bean, never {@code null}: a
     * missing container, bean or injection point, or one that now takes another type, throws
     * {@link InvalidObjectException}, and the web container drops the session. A bean manager that is not Vauban's is
     * never asked anything.
     */
    @Serial
    private Object readResolve() throws ObjectStreamException {
        if (passivationId == null || member == null || type == null) {
            throw new InvalidObjectException("an incomplete injection point reference in the stream");
        }
        if (!BUILT_INS.contains(type)) {
            throw new InvalidObjectException("the injection point " + where() + " takes " + type
                    + ", no built-in Event, Instance or Provider: a reference reads back as one of those only");
        }
        BeanManager beanManager = ImportingContainer.beanManager();
        try {
            if (!(beanManager instanceof VaubanBeanManager vauban)) {
                throw new InvalidObjectException("the importing container is not a Vauban container: "
                        + beanManager.getClass().getName());
            }
            return vauban.container().injectedBuiltIn(injectionPointOf(ImportingContainer.bean(vauban, passivationId)));
        } catch (RuntimeException e) {
            throw new InvalidObjectException("the importing container injects nothing at " + where() + ": " + e, e);
        }
    }

    /** The injection point of {@code bean} this reference names, of the type it was written with. */
    private InjectionPoint injectionPointOf(Bean<?> bean) throws InvalidObjectException {
        for (InjectionPoint candidate : bean.getInjectionPoints()) {
            if (member.equals(VaubanInjectionPoint.memberOf(candidate))
                    && position == VaubanInjectionPoint.positionOf(candidate)) {
                String candidateType = VaubanInjectionPoint.typeOf(candidate);
                if (!type.equals(candidateType)) {
                    throw new InvalidObjectException("the injection point " + where() + " takes " + candidateType
                            + " in the importing container, no longer " + type);
                }
                return candidate;
            }
        }
        throw new InvalidObjectException("no injection point " + where()
                + " in the importing container: it was renamed or removed");
    }

    /** The injection point, as a message names it. */
    private String where() {
        String point = position < 0 ? member : "parameter " + position + " of " + member;
        return point + " of the bean " + passivationId;
    }
}
