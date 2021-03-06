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

package grakn.core.concept.thing;

import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.RoleType;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public interface Relation extends Thing {

    /**
     * Get the immediate {@code RelationType} in which this this {@code Relation} is an instance of.
     *
     * @return the {@code RelationType} of this {@code Relation}
     */
    @Override
    RelationType getType();

    void addPlayer(RoleType roleType, Thing player);

    void removePlayer(RoleType roleType, Thing player);

    Stream<? extends Thing> getPlayers(String roleType, String... roleTypes);

    Stream<? extends Thing> getPlayers(RoleType... roleTypes);

    Map<? extends RoleType, ? extends List<? extends Thing>> getPlayersByRoleType();
}
