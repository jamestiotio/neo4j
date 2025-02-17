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
package org.neo4j.bolt;

import static org.neo4j.bolt.testing.assertions.BoltConnectionAssertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.neo4j.bolt.test.annotation.BoltTestExtension;
import org.neo4j.bolt.test.annotation.connection.initializer.Negotiated;
import org.neo4j.bolt.test.annotation.test.ProtocolTest;
import org.neo4j.bolt.test.annotation.wire.selector.ExcludeWire;
import org.neo4j.bolt.testing.annotation.Version;
import org.neo4j.bolt.testing.assertions.BoltConnectionAssertions;
import org.neo4j.bolt.testing.client.TransportConnection;
import org.neo4j.bolt.testing.messages.BoltWire;
import org.neo4j.bolt.testing.messages.NotificationsMessageBuilder;
import org.neo4j.bolt.transport.Neo4jWithSocketExtension;
import org.neo4j.graphdb.NotificationCategory;
import org.neo4j.graphdb.SeverityLevel;
import org.neo4j.kernel.impl.query.NotificationConfiguration;
import org.neo4j.test.extension.OtherThreadExtension;
import org.neo4j.test.extension.testdirectory.EphemeralTestDirectoryExtension;

@EphemeralTestDirectoryExtension
@Neo4jWithSocketExtension
@BoltTestExtension
@ExtendWith(OtherThreadExtension.class)
public class NotificationsConfigIT {

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldReturnWarning(BoltWire wire, @Negotiated TransportConnection connection) throws Throwable {
        connection.send(wire.hello(x -> x.withSeverity(NotificationConfiguration.Severity.WARNING)));
        connection.send(wire.logon());
        connection
                .send(wire.run("EXPLAIN MATCH (a:THIS_IS_NOT_A_LABEL) RETURN count(*)"))
                .send(wire.pull());

        BoltConnectionAssertions.assertThat(connection).receivesSuccess(3);
        // Then
        assertThat(connection)
                .receivesSuccessWithNotification(
                        "Neo.ClientNotification.Statement.UnknownLabelWarning",
                        "The provided label is not in the database.",
                        "One of the labels in your query is not available in the database, "
                                + "make sure you didn't misspell it or that the label is available when "
                                + "you run this statement in your application (the missing label name is: "
                                + "THIS_IS_NOT_A_LABEL)",
                        SeverityLevel.WARNING,
                        NotificationCategory.UNRECOGNIZED,
                        17,
                        1,
                        18);
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldSendFailureWithUnknownSeverity(BoltWire wire, @Negotiated TransportConnection connection)
            throws Throwable {
        connection.send(wire.hello(x -> x.withUnknownSeverity("WANING")));

        assertThat(connection).receivesFailure();
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldSendFailureWithUnknownCategory(BoltWire wire, @Negotiated TransportConnection connection)
            throws Throwable {
        connection.send(wire.hello(x -> x.withUnknownDisabledCategories(List.of("Pete"))));

        assertThat(connection).receivesFailure();
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldNotReturnNotificationsWhenAllDisabled(BoltWire wire, @Negotiated TransportConnection connection)
            throws Throwable {

        connection.send(wire.hello(NotificationsMessageBuilder::withDisabledNotifications));
        connection.send(wire.logon());
        connection
                .send(wire.run("EXPLAIN MATCH (a:THIS_IS_NOT_A_LABEL) RETURN count(*)"))
                .send(wire.pull());

        BoltConnectionAssertions.assertThat(connection).receivesSuccess(3);
        assertThat(connection).receivesSuccess(x -> Assertions.assertThat(x).doesNotContainKey("notifications"));
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldReturnMultipleNotifications(BoltWire wire, @Negotiated TransportConnection connection)
            throws Throwable {

        connection.send(wire.hello());
        connection.send(wire.logon());
        connection
                .send(wire.run("MATCH (a:Person) CALL { MATCH (a:Label) RETURN a AS aLabel } RETURN a"))
                .send(wire.pull());

        BoltConnectionAssertions.assertThat(connection).receivesSuccess(3);

        assertThat(connection).receivesSuccess(x -> {
            Assertions.assertThat(x).containsKey("notifications");
            Assertions.assertThat((ArrayList<?>) x.get("notifications")).hasSize(3);
        });
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldEnableNotificationsForQuery(BoltWire wire, @Negotiated TransportConnection connection)
            throws Throwable {
        connection.send(wire.hello(NotificationsMessageBuilder::withDisabledNotifications));
        connection.send(wire.logon());
        BoltConnectionAssertions.assertThat(connection).receivesSuccess(2);

        connection
                .send(wire.run("EXPLAIN MATCH (a:THIS_IS_NOT_A_LABEL) RETURN count(*)"))
                .send(wire.pull());
        assertThat(connection).receivesSuccess().receivesSuccess(x -> Assertions.assertThat(x)
                .doesNotContainKey("notifications"));
        connection
                .send(wire.run(
                        "EXPLAIN MATCH (a:THIS_IS_NOT_A_LABEL) RETURN count(*)",
                        x -> x.withSeverity(NotificationConfiguration.Severity.INFORMATION)))
                .send(wire.pull());

        assertThat(connection)
                .receivesSuccess()
                .receivesSuccessWithNotification(
                        "Neo.ClientNotification.Statement.UnknownLabelWarning",
                        "The provided label is not in the database.",
                        "One of the labels in your query is not available in the database, "
                                + "make sure you didn't misspell it or that the label is available when "
                                + "you run this statement in your application (the missing label name is: "
                                + "THIS_IS_NOT_A_LABEL)",
                        SeverityLevel.WARNING,
                        NotificationCategory.UNRECOGNIZED,
                        17,
                        1,
                        18);
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldSendFailureOnRunWithUnknownSeverity(BoltWire wire, @Negotiated TransportConnection connection)
            throws Throwable {
        connection.send(wire.hello(NotificationsMessageBuilder::withDisabledNotifications));
        connection.send(wire.logon());
        BoltConnectionAssertions.assertThat(connection).receivesSuccess(2);

        connection.send(wire.run("RETURN 1 as n", x -> x.withUnknownSeverity("boom")));

        assertThat(connection).receivesFailure();
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldSendFailureOnRunWithUnknownCategory(BoltWire wire, @Negotiated TransportConnection connection)
            throws Throwable {

        connection.send(wire.hello(NotificationsMessageBuilder::withDisabledNotifications));
        connection.send(wire.logon());
        BoltConnectionAssertions.assertThat(connection).receivesSuccess(2);

        connection.send(wire.run("RETURN 1 as n", x -> x.withUnknownDisabledCategories(List.of("boom"))));

        assertThat(connection).receivesFailure();
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldEnableNotificationsForQueryUsingCategories(
            BoltWire wire, @Negotiated TransportConnection connection) throws Throwable {

        connection.send(wire.hello(NotificationsMessageBuilder::withDisabledNotifications));
        connection.send(wire.logon());
        BoltConnectionAssertions.assertThat(connection).receivesSuccess(2);

        connection
                .send(wire.run("EXPLAIN MATCH (a:THIS_IS_NOT_A_LABEL) RETURN count(*)"))
                .send(wire.pull());
        assertThat(connection).receivesSuccess().receivesSuccess(x -> Assertions.assertThat(x)
                .doesNotContainKey("notifications"));

        connection
                .send(wire.run(
                        "EXPLAIN MATCH (a:THIS_IS_NOT_A_LABEL) RETURN count(*)",
                        x -> x.withDisabledCategories(Collections.emptyList())))
                .send(wire.pull());

        assertThat(connection)
                .receivesSuccess()
                .receivesSuccessWithNotification(
                        "Neo.ClientNotification.Statement.UnknownLabelWarning",
                        "The provided label is not in the database.",
                        "One of the labels in your query is not available in the database, "
                                + "make sure you didn't misspell it or that the label is available when "
                                + "you run this statement in your application (the missing label name is: "
                                + "THIS_IS_NOT_A_LABEL)",
                        SeverityLevel.WARNING,
                        NotificationCategory.UNRECOGNIZED,
                        17,
                        1,
                        18);
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldEnableNotificationsInBegin(BoltWire wire, @Negotiated TransportConnection connection)
            throws Throwable {

        connection.send(wire.hello(NotificationsMessageBuilder::withDisabledNotifications));
        connection.send(wire.logon());
        assertThat(connection).receivesSuccess(2);

        connection
                .send(wire.run("EXPLAIN MATCH (a:THIS_IS_NOT_A_LABEL) RETURN count(*)"))
                .send(wire.pull());
        assertThat(connection).receivesSuccess().receivesSuccess(x -> Assertions.assertThat(x)
                .doesNotContainKey("notifications"));

        connection.send(wire.begin(x -> x.withSeverity(NotificationConfiguration.Severity.WARNING)));
        assertThat(connection).receivesSuccess();

        connection
                .send(wire.run("EXPLAIN MATCH (a:THIS_IS_NOT_A_LABEL) RETURN count(*)"))
                .send(wire.pull());

        assertThat(connection)
                .receivesSuccess()
                .receivesSuccessWithNotification(
                        "Neo.ClientNotification.Statement.UnknownLabelWarning",
                        "The provided label is not in the database.",
                        "One of the labels in your query is not available in the database, "
                                + "make sure you didn't misspell it or that the label is available when "
                                + "you run this statement in your application (the missing label name is: "
                                + "THIS_IS_NOT_A_LABEL)",
                        SeverityLevel.WARNING,
                        NotificationCategory.UNRECOGNIZED,
                        17,
                        1,
                        18);
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldEnableNotificationsInBeginWithCategories(
            BoltWire wire, @Negotiated TransportConnection connection) throws Throwable {

        connection.send(wire.hello(NotificationsMessageBuilder::withDisabledNotifications));
        connection.send(wire.logon());
        BoltConnectionAssertions.assertThat(connection).receivesSuccess(2);

        connection
                .send(wire.run("EXPLAIN MATCH (a:THIS_IS_NOT_A_LABEL) RETURN count(*)"))
                .send(wire.pull());
        assertThat(connection).receivesSuccess().receivesSuccess(x -> Assertions.assertThat(x)
                .doesNotContainKey("notifications"));

        connection.send(wire.begin(x -> x.withDisabledCategories(Collections.emptyList())));
        assertThat(connection).receivesSuccess();

        connection
                .send(wire.run("EXPLAIN MATCH (a:THIS_IS_NOT_A_LABEL) RETURN count(*)"))
                .send(wire.pull());

        assertThat(connection)
                .receivesSuccess()
                .receivesSuccessWithNotification(
                        "Neo.ClientNotification.Statement.UnknownLabelWarning",
                        "The provided label is not in the database.",
                        "One of the labels in your query is not available in the database, "
                                + "make sure you didn't misspell it or that the label is available when "
                                + "you run this statement in your application (the missing label name is: "
                                + "THIS_IS_NOT_A_LABEL)",
                        SeverityLevel.WARNING,
                        NotificationCategory.UNRECOGNIZED,
                        17,
                        1,
                        18);
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldNotReturnNotificationsInDisabledCategories(
            BoltWire wire, @Negotiated TransportConnection connection) throws Throwable {
        connection.send(wire.hello(x -> x.withDisabledCategories(
                List.of(NotificationConfiguration.Category.GENERIC, NotificationConfiguration.Category.UNRECOGNIZED))));
        connection.send(wire.logon());
        connection
                .send(wire.run("MATCH (a:Person) CALL { MATCH (a:Label) RETURN a AS aLabel } RETURN a"))
                .send(wire.pull());

        BoltConnectionAssertions.assertThat(connection).receivesSuccess(3);

        assertThat(connection).receivesSuccess(x -> Assertions.assertThat(x).doesNotContainKey("notifications"));
    }

    @ProtocolTest
    @ExcludeWire({@Version(major = 4), @Version(major = 5, minor = 1, range = 1)})
    public void shouldNotReturnNotificationsWhenNotHighEnoughSeverity(
            BoltWire wire, @Negotiated TransportConnection connection) throws Throwable {
        connection.send(wire.hello(x -> x.withSeverity(NotificationConfiguration.Severity.WARNING)));
        connection.send(wire.logon());
        connection
                .send(wire.run("MATCH (a:Person) CALL { MATCH (a:Label) RETURN a AS aLabel } RETURN a"))
                .send(wire.pull());

        BoltConnectionAssertions.assertThat(connection).receivesSuccess(3);

        assertThat(connection)
                .receivesSuccess(x ->
                        Assertions.assertThat(x.get("notifications").toString()).doesNotContain("GENERIC"));
    }
}
