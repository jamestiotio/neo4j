/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.neo4j.bolt.protocol.v51.fsm.state;

import org.neo4j.bolt.protocol.common.fsm.State;
import org.neo4j.bolt.protocol.common.fsm.StateMachineContext;
import org.neo4j.bolt.protocol.common.message.request.RequestMessage;
import org.neo4j.bolt.protocol.common.message.request.authentication.LogoffMessage;
import org.neo4j.memory.HeapEstimator;

public class ReadyState extends org.neo4j.bolt.protocol.v44.fsm.state.ReadyState {
    public static final long SHALLOW_SIZE = HeapEstimator.shallowSizeOfInstance(ReadyState.class);
    AuthenticationState authenticationState;

    public void setAuthenticationState(AuthenticationState authenticationState) {
        this.authenticationState = authenticationState;
    }

    @Override
    public State processUnsafe(RequestMessage message, StateMachineContext context) throws Exception {
        if (message == LogoffMessage.getInstance()) {
            return processLogoffMessage(context);
        }

        return super.processUnsafe(message, context);
    }

    protected State processLogoffMessage(StateMachineContext context) {
        context.connection().logoff();

        return authenticationState;
    }
}
