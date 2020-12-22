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

package grakn.core.logic;

import grakn.common.collection.Pair;
import grakn.core.common.parameters.Arguments;
import grakn.core.common.parameters.Label;
import grakn.core.logic.tool.TypeResolver;
import grakn.core.pattern.Conjunction;
import grakn.core.pattern.Disjunction;
import grakn.core.pattern.variable.Variable;
import grakn.core.rocks.RocksGrakn;
import grakn.core.rocks.RocksSession;
import grakn.core.rocks.RocksTransaction;
import grakn.core.test.integration.util.Util;
import graql.lang.Graql;
import graql.lang.query.GraqlDefine;
import graql.lang.query.GraqlMatch;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static grakn.common.collection.Collections.set;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

public class TypeResolverTest {
    private static Path directory = Paths.get(System.getProperty("user.dir")).resolve("type-hinter-resolver");
    private static String database = "type-hinter-test";
    private static RocksGrakn grakn;
    private static RocksSession session;
    private static RocksTransaction transaction;

    @BeforeClass
    public static void open_session() throws IOException {
        Util.resetDirectory(directory);
        grakn = RocksGrakn.open(directory);
        grakn.databases().create(database);
        session = grakn.session(database, Arguments.Session.Type.SCHEMA);
    }

    @AfterClass
    public static void close_session() {
        session.close();
        grakn.close();
    }

    private static void define_standard_schema(String fileName) throws IOException {
        transaction = session.transaction(Arguments.Transaction.Type.WRITE);
        final GraqlDefine query = Graql.parseQuery(
                new String(Files.readAllBytes(Paths.get("test/integration/logic/" + fileName + ".gql")), UTF_8));
        transaction.query().define(query);
    }

    private static void define_custom_schema(String schema) {
        transaction = session.transaction(Arguments.Transaction.Type.WRITE);
        final GraqlDefine query = Graql.parseQuery(schema);
        transaction.query().define(query);
    }

    private Map<String, Set<String>> getHintMap(Conjunction conjunction) {
        return conjunction.variables().stream().filter(variable -> variable.reference().isName()).collect(Collectors.toMap(
                variable -> variable.reference().syntax(),
                variable -> variable.resolvedTypes().stream().map(Label::scopedName).collect(Collectors.toSet())
        ));
    }

    private Map<Pair<String, String>, Set<String>> getRoleHints(Conjunction conjunction) {
        Map<Pair<String, String>, Set<String>> ans = new HashMap<>();
        conjunction.variables().stream().filter(Variable::isThing).map(Variable::asThing)
                .filter(variable -> !variable.relation().isEmpty())
                .flatMap(variable -> variable.relation().stream())
                .flatMap(relationConstraint -> relationConstraint.players().stream())
                .forEach(rolePlayer -> {
                    if (rolePlayer.roleType().isPresent() && rolePlayer.roleType().get().reference().isName()) {
                        ans.put(new Pair<>(
                                        rolePlayer.roleType().get().reference().syntax(),
                                        rolePlayer.player().reference().syntax()
                                ),
                                rolePlayer.resolvedRoleTypes().stream().map(Label::scopedName).collect(Collectors.toSet()));
                    } else {
                        ans.put(new Pair<>("", rolePlayer.player().reference().syntax()),
                                rolePlayer.resolvedRoleTypes().stream().map(Label::scopedName).collect(Collectors.toSet()));
                    }
                });

        return ans;
    }

    private Conjunction createConjunction(String matchString) {
        GraqlMatch query = Graql.parseQuery(matchString);
        return Disjunction.create(query.conjunction().normalise()).conjunctions().iterator().next();
    }

    private Conjunction runExhaustiveHinter(TypeResolver typeResolver, String matchString) {
        return typeResolver.resolveVariablesExhaustive(createConjunction(matchString));
    }

    private Conjunction runSimpleHinter(TypeResolver typeResolver, String matchString) {
        return typeResolver.resolveVariables(createConjunction(matchString));
    }

    @Test
    public void isa_inference() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $p isa person; ";
        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$p", set("person", "man", "woman"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void isa_explicit_inference() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $p isa! person; ";
        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$p", set("person"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void is_inference() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match" +
                "  $p isa entity;" +
                "  $p is $q;" +
                "  $q isa mammal;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expectedExhaustive = new HashMap<String, Set<String>>() {{
            put("$p", set("mammal", "person", "man", "woman", "dog"));
            put("$q", set("mammal", "person", "man", "woman", "dog"));
        }};

        Map<String, Set<String>> expectedSimple = new HashMap<String, Set<String>>() {{
            put("$p", set("square", "tortoise", "woman", "mammal", "person", "animal", "man", "reptile", "dog", "triangle", "right-angled-triangle"));
            put("$q", set("mammal", "person", "man", "woman", "dog"));
        }};

        assertEquals(expectedExhaustive, getHintMap(exhaustiveConjunction));
        assertEquals(expectedSimple, getHintMap(simpleConjunction));
    }

    @Test
    public void has_inference() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $p has name 'bob';";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$p", set("person", "man", "woman", "dog"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void has_inference_variable_with_attribute_type() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $p has name $a;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$p", set("person", "man", "woman", "dog"));
            put("$a", set("name"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void has_inference_variable_without_attribute_type() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match" +
                "  $p isa shape;" +
                "  $p has $a;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$p", set("triangle", "right-angled-triangle", "square"));
            put("$a", set("perimeter", "area", "label", "hypotenuse-length"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(exhaustiveConjunction));
    }

    @Test
    public void relation_concrete_role_concrete() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $r (wife: $yoko) isa marriage;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$yoko", set("woman"));
            put("$r", set("marriage"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void relation_variable_role_concrete_relation_hidden_variable() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $r ($role: $yoko) isa marriage;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$yoko", set("person", "man", "woman"));
            put("$role", set("marriage:husband", "marriage:wife", "marriage:spouse"));
            put("$r", set("marriage"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void relation_variable_role_variable_relation_named_variable() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $r (wife: $yoko) isa $m;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$yoko", set("woman"));
            put("$r", set("marriage"));
            put("$m", set("marriage", "relation"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void relation_anon_isa() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match (wife: $yoko);";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$yoko", set("woman"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void no_role_type() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match ($yoko) isa marriage;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$yoko", set("man", "woman", "person"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void relation_multiple_roles() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $r (husband: $john, $role: $yoko, $a) isa marriage;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$yoko", set("person", "man", "woman"));
            put("$john", set("man"));
            put("$role", set("marriage:husband", "marriage:wife", "marriage:spouse"));
            put("$r", set("marriage"));
            put("$a", set("person", "man", "woman"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void has_reverse() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match" +
                "  $p isa! person;" +
                "  $p has $a;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$a", set("name", "email"));
            put("$p", set("person"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void negations_ignored() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();
        String queryString = "match" +
                "  $p isa person;" +
                "  not {$p isa man;};";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$p", set("person", "man", "woman"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void up_down_hierarchy_isa() throws IOException {
        define_custom_schema(
                "define" +
                        "  animal sub entity;" +
                        "  person sub animal;" +
                        "  man sub person;" +
                        "  greek sub man;" +
                        "  socrates sub greek;"
        );
        TypeResolver typeResolver = transaction.logic().typeResolver();
        String queryString = "match" +
                "  $p isa man;" +
                "  man sub $q;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$p", set("man", "greek", "socrates"));
            put("$q", set("thing", "entity", "animal", "person", "man"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void infer_from_value_type() throws IOException {
        define_custom_schema(
                "define" +
                        "  dog sub entity, owns weight;" +
                        "  person sub entity, owns name;" +
                        "  weight sub attribute, value double;" +
                        "  name sub attribute, value string;"
        );
        TypeResolver typeResolver = transaction.logic().typeResolver();
        String queryString = "match" +
                "  $p has $a;" +
                "  $a = 'bob';";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$p", set("person"));
            put("$a", set("name"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void plays_hierarchy() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();
        String queryString = "match (spouse: $john) isa marriage;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$john", set("person", "man", "woman"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void has_hierarchy() throws IOException {
        define_custom_schema(
                "define" +
                        "  animal sub entity, owns weight;" +
                        "  person sub animal, owns leg-weight;" +
                        "  chair sub entity, owns leg-weight;" +
                        "  dog sub animal;" +
                        "  weight sub attribute, value long, abstract;" +
                        "  leg-weight sub weight;"
        );
        TypeResolver typeResolver = transaction.logic().typeResolver();
        String queryString = "match" +
                "  $a has weight $c;" +
                "  $b has leg-weight 5;" +
                "  $p has weight $c;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$a", set("animal", "dog", "person", "chair"));
            put("$b", set("person", "chair"));
            put("$c", set("leg-weight"));
            put("$p", set("animal", "person", "dog", "chair"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }


    @Test
    public void has_with_cycle() throws IOException {
        define_custom_schema(
                "define" +
                        "  person sub entity, owns name, owns height;" +
                        "  name sub attribute, value string, owns nickname;" +
                        "  nickname sub attribute, value string, owns name;" +
                        "  surname sub attribute, value string, owns name;" +
                        "  name sub attribute, value string;" +
                        "  surname sub attribute, value string;" +
                        "  nickname sub attribute, value string;" +
                        "  height sub attribute, value double;" +
                        "  "
        );
        TypeResolver typeResolver = transaction.logic().typeResolver();
        String queryString = "match" +
                "  $a has $b;" +
                "  $b has $a;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$a", set("nickname", "name"));
            put("$b", set("nickname", "name"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void has_with_big_cycle() throws IOException {
        define_custom_schema(
                "define" +
                        "  person sub entity, owns name, owns height;" +
                        "  name sub attribute, value string, owns nickname;" +
                        "  nickname sub attribute, value string, owns surname;" +
                        "  surname sub attribute, value string, owns middlename;" +
                        "  middlename sub attribute, value string, owns name;" +
                        "  weight sub attribute, value double, owns measure-system;" +
                        "  measure-system sub attribute, value string, owns conversion-rate;" +
                        "  conversion-rate sub attribute, value double;" +
                        "  height sub attribute, value double;"
        );
        TypeResolver typeResolver = transaction.logic().typeResolver();
        String queryString = "match" +
                "  $a has $b;" +
                "  $b has $c;" +
                "  $c has $d;" +
                "  $d has $a;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expectedExhaustive = new HashMap<String, Set<String>>() {{
            put("$a", set("name", "surname", "nickname", "middlename"));
            put("$b", set("name", "surname", "nickname", "middlename"));
            put("$c", set("name", "surname", "nickname", "middlename"));
            put("$d", set("name", "surname", "nickname", "middlename"));
        }};

        Map<String, Set<String>> expectedSimple = new HashMap<String, Set<String>>() {{
            put("$a", set("name", "surname", "nickname", "middlename", "measure-system"));
        }};

        assertEquals(expectedExhaustive, getHintMap(exhaustiveConjunction));
        assertEquals(expectedExhaustive, getHintMap(simpleConjunction));
    }

    @Test
    public void you_know_the_thing() throws IOException {
        define_standard_schema("basic-schema");
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $x isa thing;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$x", Collections.emptySet());
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    @Test
    public void branched_isa() throws IOException {
        define_custom_schema(
                "define" +
                        "  person sub entity;" +
                        "  man sub person, owns man-name;" +
                        "  woman sub person, owns woman-name;" +
                        "  man-name sub attribute, value string;" +
                        "  woman-name sub attribute, value string;" +
                        ""
        );
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $x isa $t; $y isa $t; $x has man-name'bob'; $y has woman-name 'alice';";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expectedExhaustive = new HashMap<String, Set<String>>() {{
            put("$x", set("man"));
            put("$y", set("woman"));
            put("$t", set("thing", "entity", "person"));
        }};

        Map<String, Set<String>> expectedSimple = new HashMap<String, Set<String>>() {{
            put("$x", set("man"));
            put("$y", set("woman"));
            put("$t", set("thing", "entity", "person", "man", "woman"));
        }};

        assertEquals(expectedExhaustive, getHintMap(exhaustiveConjunction));
        assertEquals(expectedSimple, getHintMap(simpleConjunction));
    }

    @Test
    public void simple_always_infers_its_supers() {
        define_custom_schema(
                "define" +
                        "  animal sub entity;" +
                        "  person sub animal;" +
                        "  man sub person;" +
                        "  greek sub man;" +
                        "  socrates sub greek;"
        );
        TypeResolver typeResolver = transaction.logic().typeResolver();

        String queryString = "match $x isa $y;" +
                "  $y sub $z;" +
                "  $z sub $w;" +
                "  $w sub person;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$x", set("person", "man", "greek", "socrates"));
            put("$y", set("person", "man", "greek", "socrates"));
            put("$z", set("person", "man", "greek", "socrates"));
            put("$w", set("person", "man", "greek", "socrates"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

    // When a hint label exists, it can "skip" a generation, meaning a hint and the hint's descendent is possible, yet
    // none of the hint's direct children are possible.
    // We show this below on the hint labels of $t
    // We also show on $a that hints can be isolated form each other completely hierarchy-wise.
    @Test
    @Ignore
    public void hierarchy_hint_gap() throws IOException {
        define_custom_schema(
                "define " +
                        "  animal sub entity;" +
                        "  left-attr sub attribute, value boolean;" +
                        "  right-attr sub attribute, value boolean;" +
                        "  ownership-attr sub attribute, value boolean;" +
                        "  marriage-attr sub attribute, value boolean;" +
                        "  animal sub entity, owns ownership-attr; " +
                        "  mammal sub animal; " +
                        "  person sub mammal, plays ownership:owner, owns marriage-attr; " +
                        "  man sub person, plays marriage:husband, owns left-attr; " +
                        "  woman sub person, plays marriage:wife, owns right-attr; " +
                        "  tortoise sub animal, plays ownership:pet, owns left-attr; " +
                        "  marriage sub relation, relates husband, relates wife, owns marriage-attr; " +
                        "  ownership sub relation, relates pet, relates owner, owns ownership-attr;"
        );
        TypeResolver typeResolver = transaction.logic().typeResolver();
        String queryString = "match " +
                "  $a isa $t; " +
                "  $b isa $t; " +
                "  $t owns $c; " +
                "  $t sub entity; " +
                "  ($a, $b) isa $rel; " +
                "  $rel owns $c; " +
                "  $a has left-attr true; " +
                "  $b has right-attr true;";

        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$t", set("animal", "person"));
            put("$a", set("tortoise", "man"));
            put("$b", set("woman"));
            put("$rel", set("ownership", "marriage"));
            put("$c", set("ownership-attr", "marriage-attr"));
        }};
        assertEquals(expected, getHintMap(exhaustiveConjunction));
    }

    @Test
    public void multiple_anonymous_vars() throws IOException {
        define_standard_schema("basic-schema");
        String queryString = "match $a has name 'fido'; $a has label 'poodle';";
        TypeResolver typeResolver = transaction.logic().typeResolver();
        Conjunction exhaustiveConjunction = runExhaustiveHinter(typeResolver, queryString);
        Conjunction simpleConjunction = runSimpleHinter(typeResolver, queryString);

        Map<String, Set<String>> expected = new HashMap<String, Set<String>>() {{
            put("$a", set("dog"));
        }};

        assertEquals(expected, getHintMap(exhaustiveConjunction));
        assertEquals(expected, getHintMap(simpleConjunction));
    }

}