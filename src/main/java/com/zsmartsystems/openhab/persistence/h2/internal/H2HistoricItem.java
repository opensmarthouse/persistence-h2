/**
 * Copyright (c) 2014-2016 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package com.zsmartsystems.openhab.persistence.h2.internal;

import java.text.DateFormat;
import java.time.ZonedDateTime;
import java.util.Date;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.types.State;

/**
 * This is a Java bean used to return historic items from a SQL database.
 *
 * @author Chris Jackson - Initial contribution
 *
 */
public class H2HistoricItem implements HistoricItem {

    final private String name;
    final private State state;
    final private ZonedDateTime timestamp;

    public H2HistoricItem(String name, State state, ZonedDateTime timestamp) {
        this.name = name;
        this.state = state;
        this.timestamp = timestamp;
    }

    public String getName() {
        return name;
    }

    public State getState() {
        return state;
    }

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return DateFormat.getDateTimeInstance().format(timestamp) + ": " + name + " -> " + state.toString();
    }

}