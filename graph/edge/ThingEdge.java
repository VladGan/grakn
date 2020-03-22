/*
 * Copyright (C) 2020 Grakn Labs
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
 *
 */

package hypergraph.graph.edge;

import hypergraph.graph.Graph;
import hypergraph.graph.Schema;
import hypergraph.graph.vertex.ThingVertex;

public abstract class ThingEdge extends Edge<Schema.Edge.Thing, ThingVertex> {

    ThingEdge(Graph graph, byte[] iid, Schema.Edge.Thing schema, ThingVertex from, ThingVertex to) {
        super(graph, iid, schema, from, to);
    }

    public static class Buffered extends ThingEdge {

        public Buffered(Graph graph, Schema.Edge.Thing schema, ThingVertex from, ThingVertex to) {
            super(graph, null, schema, from, to);
        }

        @Override
        public Schema.Status status() {
            return Schema.Status.BUFFERED;
        }

        @Override
        public ThingVertex from() {
            return from;
        }

        @Override
        public ThingVertex to() {
            return to;
        }

        @Override
        public void commit() {
            // TODO
        }
    }

    public static class Persisted extends ThingEdge {

        public Persisted(Graph graph, byte[] iid) {
            super(graph, iid, null, null, null); // TODO
        }

        @Override
        public Schema.Status status() {
            return Schema.Status.PERSISTED;
        }

        @Override
        public ThingVertex from() {
            return null; // TODO
        }

        @Override
        public ThingVertex to() {
            return null; // TODO
        }

        @Override
        public void commit() {}
    }
}
