/*
 * Copyright (C) 2021 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.test.integration.covid;

import grakn.core.common.iterator.ResourceIterator;
import grakn.core.common.parameters.Arguments;
import grakn.core.common.parameters.Context;
import grakn.core.common.parameters.Options;
import grakn.core.concept.answer.ConceptMap;
import grakn.core.rocks.RocksGrakn;
import grakn.core.rocks.RocksSession;
import grakn.core.rocks.RocksTransaction;
import graql.lang.Graql;
import graql.lang.query.GraqlMatch;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static graql.lang.Graql.var;

public class CovidTest {
    private static final Path directory = Paths.get("/Users/vladyslavhanzha/work/grakn/test/integration/covid/data");
    private static String database = "biograkn_covid_19";

    @Test
    public void running_query() {
        GraqlMatch query = Graql.match(
                var().rel("x").rel("y"),
                var().rel("y").rel("z"),
                var().rel("x").rel("z")
        );

        try (RocksGrakn grakn = RocksGrakn.open(directory)) {
            try (RocksSession session = grakn.session(database, Arguments.Session.Type.DATA)) {
                try (RocksTransaction txn = session.transaction(Arguments.Transaction.Type.READ)) {
                    Options.Query options = new Options.Query();
                    options.parallel(false);
                    Context.Query context = new Context.Query(txn.context(), options);
                    ResourceIterator<ConceptMap> answers = txn.query().match(query);
                    while (answers.hasNext())
                        System.out.println(answers.next());
                    System.out.println("FINISH");
                }
            }
        }

    }
}
