package org.ggp.base.validator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import org.ggp.base.util.game.Game;
import org.ggp.base.util.game.GameRepository;
import org.ggp.base.util.game.TestGameRepository;
import org.ggp.base.util.gdl.GdlVisitor;
import org.ggp.base.util.gdl.GdlVisitors;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlDistinct;
import org.ggp.base.util.gdl.grammar.GdlFunction;
import org.ggp.base.util.gdl.grammar.GdlLiteral;
import org.ggp.base.util.gdl.grammar.GdlNot;
import org.ggp.base.util.gdl.grammar.GdlOr;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlProposition;
import org.ggp.base.util.gdl.grammar.GdlRelation;
import org.ggp.base.util.gdl.grammar.GdlRule;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.gdl.grammar.GdlVariable;
import org.ggp.base.util.gdl.model.DependencyGraphs;

import com.google.common.base.Predicates;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

public class StaticValidator implements GameValidator {
    private static final GdlConstant ROLE = GdlPool.getConstant("role");
    private static final GdlConstant TERMINAL = GdlPool.getConstant("terminal");
    private static final GdlConstant GOAL = GdlPool.getConstant("goal");
    private static final GdlConstant LEGAL = GdlPool.getConstant("legal");
    private static final GdlConstant DOES = GdlPool.getConstant("does");
    private static final GdlConstant INIT = GdlPool.getConstant("init");
    private static final GdlConstant TRUE = GdlPool.getConstant("true");
    private static final GdlConstant NEXT = GdlPool.getConstant("next");
    private static final GdlConstant BASE = GdlPool.getConstant("base");
    private static final GdlConstant INPUT = GdlPool.getConstant("input");

    /**
     * Validates whether a GDL description follows the rules of GDL,
     * to the extent that it can be determined. If the description is
     * invalid, throws an exception of type ValidatorException
     * explaining the problem.
     *
     * Features like finitism and monotonicity can't be definitively determined
     * with a static analysis; these are left to the other validator. (See
     * GdlValidator and the ValidatorPanel in apps.validator.)
     *
     * @param description A parsed GDL game description.
     * @throws ValidatorException The description did not pass validation.
     * The error message explains the error found in the GDL description.
     */
    public static List<ValidatorWarning> validateDescription(List<Gdl> description) throws ValidatorException {
        /* This assumes that the description is already well-formed enough
         * to be made into a list of Gdl objects. We need to check those
         * remaining features that can be verified here.
         *
         * A + is an implemented test; a - is not (fully) implemented.
         *
         * Features of negated datalog with functions:
         * + All negations apply directly to sentences
         * + All rules are safe: All variables in the rule must appear
         *   in some positive relation in the body.
         * + Arities of relation constants and function constants are fixed
         * + The rules are stratified: The dependency graph generated by
         *   the rules must contain no cycles with a negative edge.
         * + Added restriction on functions and recursion
         * Additional features of GDL:
         * + Role relations must be ground sentences, not in rules
         * + Inits only in heads of rules; not in same CC as true, does, next, legal, goal, terminal
         * + Trues only in bodies of rules, not heads
         * + Nexts only in heads of rules
         * + Does only in bodies of rules; no paths between does and legal/goal/terminal
         * + Bases and inputs only in heads of rules, not downstream of true or does
         *
         * + Arities: Role 1, true 1, init 1, next 1, legal 2, does 2, goal 2, terminal 0
         * - Legal's first argument must be a player; ditto does, goal
         * - Goal values are integers between 0 and 100
         * Misc.:
         * + All objects are relations or rules
         * + (warning) Sentence names used in the bodies of rules are defined somewhere
         *
         * Things we can't really test here:
         * - In all cases, valid arguments to does, goal, legal
         * - Game terminality
         * - Players have goals in all states, exactly one goal in each state,
         *   and those goals are monotonic
         * - Playability: legal moves for each player in each non-terminal state
         * - Weak winnability
         *
         * Reference for the restrictions: http://games.stanford.edu/language/spec/gdl_spec_2008_03.pdf
         */
        List<ValidatorWarning> warnings = Lists.newArrayList();

        List<GdlRelation> relations = Lists.newArrayList();
        List<GdlRule> rules = Lists.newArrayList();
        //1) Are all objects in the description rules or relations?
        for (Gdl gdl : description) {
            if (gdl instanceof GdlRelation) {
                relations.add((GdlRelation) gdl);
            } else if (gdl instanceof GdlRule) {
                rules.add((GdlRule) gdl);
            } else if (gdl instanceof GdlProposition) {
                warnings.add(new ValidatorWarning("StaticValidator warning: The rules contain the GdlProposition " + gdl + ", which may not be intended."));
            } else {
                throw new ValidatorException("The rules include a GDL object of type " + gdl.getClass().getSimpleName() + ". Only GdlRelations and GdlRules are expected. The Gdl object is: " + gdl);
            }
        }
        //2) Do all objects parsed as functions, relations, and rules have non-zero arity?
        verifyNonZeroArities(relations, rules);
        //3) Do all negations apply directly to sentences?
        for(GdlRule rule : rules) {
            for(GdlLiteral literal : rule.getBody()) {
                testLiteralForImproperNegation(literal);
            }
        }
        //4) Are the arities of all relations and all functions fixed?
        Map<GdlConstant, Integer> sentenceArities = new HashMap<GdlConstant, Integer>();
        Map<GdlConstant, Integer> functionArities = new HashMap<GdlConstant, Integer>();
        for(GdlRelation relation : relations) {
            addSentenceArity(relation, sentenceArities);
            addFunctionArities(relation, functionArities);
        }
        for(GdlRule rule : rules) {
            List<GdlSentence> sentences = getSentencesInRule(rule);
            for(GdlSentence sentence : sentences) {
                addSentenceArity(sentence, sentenceArities);
                addFunctionArities(sentence, functionArities);
            }
        }
        checkSentenceFunctionNameOverlap(sentenceArities, functionArities, warnings);
        //5) Are the arities of the GDL-defined relations correct?
        //6) Do any functions have the names of GDL keywords (likely an error)?
        testPredefinedArities(sentenceArities, functionArities);

        //7) Are all rules safe?
        for(GdlRule rule : rules) {
            testRuleSafety(rule);
        }

        //8) Are the rules stratified? (Checked as part of dependency graph generation)
        //This dependency graph is actually based on relation constants, not sentence forms (like some of the other tools here)
        SetMultimap<GdlConstant, GdlConstant> dependencyGraph =
                getDependencyGraphAndValidateNoNegativeCycles(sentenceArities.keySet(), rules);

        //9) We check that all the keywords are related to one another correctly, according to the dependency graph
        checkKeywordLocations(dependencyGraph, sentenceArities.keySet());

        //10) We check the restriction on functions and recursion
        Map<GdlConstant, Set<GdlConstant>> ancestorsGraph = getAncestorsGraph(dependencyGraph, sentenceArities.keySet());
        for(GdlRule rule : rules) {
            checkRecursionFunctionRestriction(rule, ancestorsGraph);
        }

        checkSentencesUsedInRuleBodiesAreDefinedSomewhere(rules, relations, warnings);

        return warnings;
    }

    private static void checkSentenceFunctionNameOverlap(
            Map<GdlConstant, Integer> sentenceArities,
            Map<GdlConstant, Integer> functionArities,
            List<ValidatorWarning> warnings) {
        for (GdlConstant sentenceName : sentenceArities.keySet()) {
            if (functionArities.containsKey(sentenceName)) {
                warnings.add(new ValidatorWarning("The constant " + sentenceName + " is used as both a " +
                        "sentence name and as a function name. This is probably unintended. Are you using " +
                        "'true' correctly?"));
            }
        }
    }

    private static void verifyNonZeroArities(List<GdlRelation> relations,
            List<GdlRule> rules) throws ValidatorException {
        GdlVisitor arityCheckingVisitor = new GdlVisitor() {
            @Override
            public void visitFunction(GdlFunction function) {
                if (function.arity() == 0) {
                    throw new RuntimeException(function + " is written as a zero-arity function; " +
                            "it should be written as a constant instead. " +
                            "(Try dropping the parentheses.)");
                }
            }
            @Override
            public void visitRelation(GdlRelation relation) {
                if (relation.arity() == 0) {
                    throw new RuntimeException(relation + " is written as a zero-arity relation; " +
                            "it should be written as a proposition instead. " +
                            "(Try dropping the parentheses.)");
                }
            }
            @Override
            public void visitRule(GdlRule rule) {
                if (rule.arity() == 0) {
                    throw new RuntimeException(rule + " is written as a zero-arity rule; " +
                            "if it's always supposed to be true, it should be written as a " +
                            "relation instead. Otherwise, check your parentheses.");
                }
            }
        };
        try {
            GdlVisitors.visitAll(relations, arityCheckingVisitor);
            GdlVisitors.visitAll(rules, arityCheckingVisitor);
        } catch (RuntimeException e) {
            throw new ValidatorException(e.getMessage());
        }
    }

    /**
     * Tests whether the parentheses in a given file match correctly. If the
     * parentheses are unbalanced, gives the line number of an unmatched
     * parenthesis.
     * @param file The .kif file to test.
     * @throws ValidatorException The parentheses are unbalanced. The
     * line number of an unmatched parenthesis is included in the error
     * message.
     */
    public static void matchParentheses(File file) throws ValidatorException {
        List<String> lines = new ArrayList<String>();
        try (BufferedReader in = new BufferedReader(new FileReader(file))) {
            String line;
            while((line = in.readLine()) != null) {
                lines.add(line);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        matchParentheses(lines.toArray(new String[]{}));
    }
    private static void matchParentheses(String[] lines) throws ValidatorException {
        int lineNumber = 1;
        Stack<Integer> linesStack = new Stack<Integer>();

        for (String line : lines) {
            for(int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if(c == '(') {
                    linesStack.add(lineNumber);
                } else if(c == ')') {
                    if(linesStack.isEmpty()) {
                        throw new ValidatorException("Extra close parens encountered at line " + lineNumber + "\nLine: " + line);
                    }
                    linesStack.pop();
                } else if(c == ';') {
                    //the line is a comment; ignore its parens
                    break;
                }
            }
            lineNumber++;
        }

        if(!linesStack.isEmpty()) {
            throw new ValidatorException("Extra open parens encountered, starting at line " + linesStack.peek());
        }
    }

    private static void checkRecursionFunctionRestriction(GdlRule rule,
            Map<GdlConstant, Set<GdlConstant>> ancestorsGraph) throws ValidatorException {
        //TODO: This might not work 100% correctly with descriptions with
        //"or" in them, especially if ors are nested. For best results, deOR
        //before testing.
        //The restriction goes something like this:
        //Look at all the terms in each positive relation in the rule that
        // is in a cycle with the head.
        GdlConstant head = rule.getHead().getName();
        Set<GdlRelation> cyclicRelations = new HashSet<GdlRelation>();
        Set<GdlRelation> acyclicRelations = new HashSet<GdlRelation>();
        for(GdlLiteral literal : rule.getBody()) {
            //Is it a relation?
            if(literal instanceof GdlRelation) {
                GdlRelation relation = (GdlRelation) literal;
                //Is it in a cycle with the head?
                if(ancestorsGraph.get(relation.getName()).contains(head)) {
                    cyclicRelations.add(relation);
                } else {
                    acyclicRelations.add(relation);
                }
            } else if(literal instanceof GdlOr) {
                //We'll look one layer deep for the cyclic kind
                GdlOr or = (GdlOr) literal;
                for(int i = 0; i < or.arity(); i++) {
                    GdlLiteral internal = or.get(i);
                    if(internal instanceof GdlRelation) {
                        GdlRelation relation = (GdlRelation) internal;
                        if(ancestorsGraph.get(relation.getName()).contains(head)) {
                            cyclicRelations.add(relation);
                        } //Don't add acyclic relations, as we can't count on them
                    }
                }
            }
        }

        for(GdlRelation relation : cyclicRelations) {
            for(GdlTerm term : relation.getBody()) {
                //There are three ways to be okay
                boolean safe = false;
                //One: Is it ground?
                if(term.isGround())
                    safe = true;

                //Two: Is it a term in the head relation?
                if(rule.getHead() instanceof GdlRelation) {
                    for(GdlTerm headTerm : rule.getHead().getBody()) {
                        if(headTerm.equals(term))
                            safe = true;
                    }
                }

                //Three: Is it in some other positive conjunct not in a cycle with the head?
                for(GdlRelation acyclicRelation : acyclicRelations) {
                    for(GdlTerm acyclicTerm : acyclicRelation.getBody()) {
                        if(acyclicTerm.equals(term))
                            safe = true;
                    }
                }
                if(!safe)
                    throw new ValidatorException("Recursion-function restriction violated in rule " + rule + ", for term " + term);
            }

        }
    }

    private static Map<GdlConstant, Set<GdlConstant>> getAncestorsGraph(
            SetMultimap<GdlConstant, GdlConstant> dependencyGraph,
            Set<GdlConstant> allSentenceNames) {
        Map<GdlConstant, Set<GdlConstant>> ancestorsGraph = Maps.newHashMap();
        for (GdlConstant sentenceName : allSentenceNames) {
            ancestorsGraph.put(sentenceName, DependencyGraphs.getMatchingAndUpstream(
                    allSentenceNames, dependencyGraph, Predicates.equalTo(sentenceName)));
        }
        return ancestorsGraph;
    }

    private static final ImmutableSet<GdlConstant> NEVER_IN_RULE_BODIES =
            ImmutableSet.of(INIT, NEXT, BASE, INPUT);
    private static final ImmutableList<GdlConstant> NEVER_TURN_DEPENDENT =
            ImmutableList.of(INIT, BASE, INPUT);
    private static final ImmutableList<GdlConstant> NEVER_ACTION_DEPENDENT =
            ImmutableList.of(TERMINAL, LEGAL, GOAL);
    private static void checkKeywordLocations(
            SetMultimap<GdlConstant, GdlConstant> dependencyGraph,
            Set<GdlConstant> allSentenceNames) throws ValidatorException {
        //- Role relations must be ground sentences, not in rules
        if(!dependencyGraph.get(ROLE).isEmpty())
            throw new ValidatorException("The role relation should be defined by ground statements, not by rules.");
        //- Trues only in bodies of rules, not heads
        if(!dependencyGraph.get(TRUE).isEmpty())
            throw new ValidatorException("The true relation should never be in the head of a rule.");
        //- Does only in bodies of rules
        if(!dependencyGraph.get(DOES).isEmpty())
            throw new ValidatorException("The does relation should never be in the head of a rule.");

        //- Inits only in heads of rules; not in same CC as true, does, next, legal, goal, terminal
        //- Bases and inputs follow the same rules as init
        //- Nexts only in heads of rules
        for(GdlConstant relNameInBody : dependencyGraph.values()) {
            if(NEVER_IN_RULE_BODIES.contains(relNameInBody)) {
                throw new ValidatorException("The " + relNameInBody + " relation should never be in the body of a rule.");
            }
        }
        ImmutableSet<GdlConstant> turnDependentSentenceNames = DependencyGraphs.getMatchingAndDownstream(allSentenceNames, dependencyGraph,
                Predicates.in(ImmutableSet.of(TRUE, DOES, NEXT, LEGAL, GOAL, TERMINAL)));
        for (GdlConstant keyword : NEVER_TURN_DEPENDENT) {
            if (turnDependentSentenceNames.contains(keyword)) {
                throw new ValidatorException("A " + keyword + " relation should never have a dependency on a true, does, next, legal, goal, or terminal sentence.");
            }
        }

        //- No paths between does and legal/goal/terminal
        ImmutableSet<GdlConstant> actionDependentSentenceNames = DependencyGraphs.getMatchingAndDownstream(allSentenceNames, dependencyGraph,
                Predicates.equalTo(DOES));
        for (GdlConstant keyword : NEVER_ACTION_DEPENDENT) {
            if (actionDependentSentenceNames.contains(keyword)) {
                throw new ValidatorException("A " + keyword + " relation should never have a dependency on a does sentence.");
            }
        }
    }


    private static SetMultimap<GdlConstant, GdlConstant> getDependencyGraphAndValidateNoNegativeCycles(
            Set<GdlConstant> relationNames, List<GdlRule> rules) throws ValidatorException {
        SetMultimap<GdlConstant, GdlConstant> dependencyGraph = HashMultimap.create();
        SetMultimap<GdlConstant, GdlConstant> negativeEdges = HashMultimap.create();
        for(GdlRule rule : rules) {
            GdlConstant headName = rule.getHead().getName();
            for(GdlLiteral literal : rule.getBody()) {
                addLiteralAsDependent(literal, dependencyGraph.get(headName), negativeEdges.get(headName));
            }
        }

        checkForNegativeCycles(dependencyGraph, negativeEdges, relationNames);

        return dependencyGraph;
    }
    private static void checkForNegativeCycles(
            SetMultimap<GdlConstant, GdlConstant> dependencyGraph,
            SetMultimap<GdlConstant, GdlConstant> negativeEdges,
            Set<GdlConstant> allNames) throws ValidatorException {
        while(!negativeEdges.isEmpty()) {
            //Look for a cycle containing this edge
            GdlConstant tail = negativeEdges.keySet().iterator().next();
            Set<GdlConstant> heads = negativeEdges.get(tail);
            negativeEdges.removeAll(tail);

            for(GdlConstant head : heads) {
                Set<GdlConstant> upstreamNames =
                        DependencyGraphs.getMatchingAndUpstream(allNames, dependencyGraph, Predicates.equalTo(head));
                if (upstreamNames.contains(tail)) {
                    throw new ValidatorException("There is a negative edge from " + tail + " to " + head + " in a cycle in the dependency graph");
                }
            }
        }
    }

    private static void addLiteralAsDependent(GdlLiteral literal,
            Set<GdlConstant> dependencies, Set<GdlConstant> negativeEdges) {
        if(literal instanceof GdlSentence) {
            dependencies.add(((GdlSentence) literal).getName());
        } else if(literal instanceof GdlNot) {
            addLiteralAsDependent(((GdlNot) literal).getBody(), dependencies, negativeEdges);
            addLiteralAsDependent(((GdlNot) literal).getBody(), negativeEdges, negativeEdges);
        } else if(literal instanceof GdlOr) {
            GdlOr or = (GdlOr) literal;
            for(int i = 0; i < or.arity(); i++) {
                addLiteralAsDependent(or.get(i), dependencies, negativeEdges);
            }
        }
    }

    private static void testRuleSafety(GdlRule rule) throws ValidatorException {
        List<GdlVariable> unsupportedVariables = new ArrayList<GdlVariable>();
        if(rule.getHead() instanceof GdlRelation)
            getVariablesInBody(rule.getHead().getBody(), unsupportedVariables);
        for(GdlLiteral literal : rule.getBody()) {
            getUnsupportedVariablesInLiteral(literal, unsupportedVariables);
        }
        //Supported variables are those in a positive relation in the body
        Set<GdlVariable> supportedVariables = new HashSet<GdlVariable>();
        for(GdlLiteral literal : rule.getBody()) {
            getSupportedVariablesInLiteral(literal, supportedVariables);
        }
        for(GdlVariable var : unsupportedVariables)
            if(!supportedVariables.contains(var))
                throw new ValidatorException("Unsafe rule " + rule + ": Variable " + var + " is not defined in a positive relation in the rule's body");
    }
    private static void getUnsupportedVariablesInLiteral(GdlLiteral literal,
            Collection<GdlVariable> unsupportedVariables) {
        //We're looking for all variables in distinct or negated relations
        if(literal instanceof GdlNot) {
            GdlLiteral internal = ((GdlNot) literal).getBody();
            if(internal instanceof GdlRelation) {
                getVariablesInBody(((GdlRelation) internal).getBody(), unsupportedVariables);
            }
        } else if(literal instanceof GdlOr) {
            GdlOr or = (GdlOr) literal;
            for(int i = 0; i < or.arity(); i++) {
                getUnsupportedVariablesInLiteral(or.get(i), unsupportedVariables);
            }
        } else if(literal instanceof GdlDistinct) {
            GdlDistinct distinct = (GdlDistinct) literal;
            List<GdlTerm> pair = new ArrayList<GdlTerm>(2); //Easy way to parse functions
            pair.add(distinct.getArg1());
            pair.add(distinct.getArg2());
            getVariablesInBody(pair, unsupportedVariables);
        }
    }
    private static void getSupportedVariablesInLiteral(GdlLiteral literal,
            Collection<GdlVariable> variables) {
        if(literal instanceof GdlRelation) {
            getVariablesInBody(((GdlRelation) literal).getBody(), variables);
        } else if(literal instanceof GdlOr) {
            GdlOr or = (GdlOr) literal;
            if(or.arity() == 0)
                return;
            LinkedList<GdlVariable> vars = new LinkedList<GdlVariable>();
            getSupportedVariablesInLiteral(or.get(0), vars);
            for(int i = 1; i < or.arity(); i++) {
                Set<GdlVariable> newVars = new HashSet<GdlVariable>();
                getSupportedVariablesInLiteral(or.get(i), newVars);
                vars.retainAll(newVars);
            }
            variables.addAll(vars);
        }
    }
    private static void getVariablesInBody(List<GdlTerm> body,
            Collection<GdlVariable> variables) {
        for(GdlTerm term : body) {
            if(term instanceof GdlVariable) {
                variables.add((GdlVariable) term);
            } else if(term instanceof GdlFunction) {
                getVariablesInBody(((GdlFunction) term).getBody(), variables);
            }
        }
    }


    private static void testPredefinedArities(
            Map<GdlConstant, Integer> sentenceArities,
            Map<GdlConstant, Integer> functionArities) throws ValidatorException {
        if(!sentenceArities.containsKey(ROLE)) {
            throw new ValidatorException("No role relations found in the game description");
        } else if(sentenceArities.get(ROLE) != 1) {
            throw new ValidatorException("The role relation should have arity 1 (argument: the player name)");
        } else if(!sentenceArities.containsKey(TERMINAL)) {
            throw new ValidatorException("No terminal proposition found in the game description");
        } else if(sentenceArities.get(TERMINAL) != 0) {
            throw new ValidatorException("'terminal' should be a proposition, not a relation");
        } else if(!sentenceArities.containsKey(GOAL)) {
            throw new ValidatorException("No goal relations found in the game description");
        } else if(sentenceArities.get(GOAL) != 2) {
            throw new ValidatorException("The goal relation should have arity 2 (first argument: the player, second argument: integer from 0 to 100)");
        } else if(!sentenceArities.containsKey(LEGAL)) {
            throw new ValidatorException("No legal relations found in the game description");
        } else if(sentenceArities.get(LEGAL) != 2) {
            throw new ValidatorException("The legal relation should have arity 2 (first argument: the player, second argument: the move)");
        } else if(sentenceArities.containsKey(DOES) && sentenceArities.get(DOES) != 2) {
            throw new ValidatorException("The does relation should have arity 2 (first argument: the player, second argument: the move)");
        } else if(sentenceArities.containsKey(INIT) && sentenceArities.get(INIT) != 1) {
            throw new ValidatorException("The init relation should have arity 1 (argument: the base truth)");
        } else if(sentenceArities.containsKey(TRUE) && sentenceArities.get(TRUE) != 1) {
            throw new ValidatorException("The true relation should have arity 1 (argument: the base truth)");
        } else if(sentenceArities.containsKey(NEXT) && sentenceArities.get(NEXT) != 1) {
            throw new ValidatorException("The next relation should have arity 1 (argument: the base truth)");
        } else if(sentenceArities.containsKey(BASE) && sentenceArities.get(BASE) != 1) {
            throw new ValidatorException("The base relation should have arity 1 (argument: the base truth)");
        } else if(sentenceArities.containsKey(INPUT) && sentenceArities.get(INPUT) != 2) {
            throw new ValidatorException("The input relation should have arity 2 (first argument: the player, second argument: the move)");
        }

        //Look for function arities with these names
        for (GdlConstant functionName : functionArities.keySet()) {
            if (GdlPool.KEYWORDS.contains(functionName.getValue())) {
                throw new ValidatorException("The keyword " + functionName + " is being used as a function. It should only be used as the name of a sentence.");
            }
        }
    }


    private static void addSentenceArity(GdlSentence sentence,
            Map<GdlConstant, Integer> sentenceArities) throws ValidatorException {
        Integer curArity = sentenceArities.get(sentence.getName());
        if(curArity == null) {
            sentenceArities.put(sentence.getName(), sentence.arity());
        } else if(curArity != sentence.arity()) {
            throw new ValidatorException("The sentence with the name " + sentence.getName() + " appears with two different arities, " + sentence.arity() + " and " + curArity + ".");
        }
    }
    private static void addFunctionArities(GdlSentence sentence,
            Map<GdlConstant, Integer> functionArities) throws ValidatorException {
        for(GdlFunction function : getFunctionsInSentence(sentence)) {
            Integer curArity = functionArities.get(function.getName());
            if(curArity == null) {
                functionArities.put(function.getName(), function.arity());
            } else if(curArity != function.arity()) {
                throw new ValidatorException("The function with the name " + function.getName() + " appears with two different arities, " + function.arity() + " and " + curArity);
            }
        }
    }
    private static List<GdlSentence> getSentencesInRule(GdlRule rule) {
        List<GdlSentence> sentences = new ArrayList<GdlSentence>();
        sentences.add(rule.getHead());
        for(GdlLiteral literal : rule.getBody()) {
            getSentencesInLiteral(literal, sentences);
        }
        return sentences;
    }
    private static void getSentencesInLiteral(GdlLiteral literal, List<GdlSentence> sentences) {
        if(literal instanceof GdlSentence) {
            sentences.add((GdlSentence) literal);
        } else if(literal instanceof GdlNot) {
            getSentencesInLiteral(((GdlNot) literal).getBody(), sentences);
        } else if(literal instanceof GdlOr) {
            GdlOr or = (GdlOr) literal;
            for(int i = 0; i < or.arity(); i++) {
                getSentencesInLiteral(or.get(i), sentences);
            }
        }
    }
    private static List<GdlFunction> getFunctionsInSentence(GdlSentence sentence) {
        List<GdlFunction> functions = new ArrayList<GdlFunction>();
        if(sentence instanceof GdlProposition)
            return functions; //Propositions have no body
        addFunctionsInBody(sentence.getBody(), functions);
        return functions;
    }
    private static void addFunctionsInBody(List<GdlTerm> body,
            List<GdlFunction> functions) {
        for(GdlTerm term : body) {
            if(term instanceof GdlFunction) {
                GdlFunction function = (GdlFunction) term;
                functions.add(function);
                addFunctionsInBody(function.getBody(), functions);
            }
        }
    }


    private static void testLiteralForImproperNegation(GdlLiteral literal) throws ValidatorException {
        if(literal instanceof GdlNot) {
            GdlNot not = (GdlNot) literal;
            if(!(not.getBody() instanceof GdlSentence))
                throw new ValidatorException("The negation " + not + " contains a literal " + not.getBody() + " that is not a sentence. Only a single sentence is allowed inside a negation.");
        } else if(literal instanceof GdlOr) {
            GdlOr or = (GdlOr) literal;
            for(int i = 0; i < or.arity(); i++) {
                testLiteralForImproperNegation(or.get(i));
            }
        }
    }

    private static void checkSentencesUsedInRuleBodiesAreDefinedSomewhere(
            List<GdlRule> rules, List<GdlRelation> relations, List<ValidatorWarning> warnings) {
        Set<GdlConstant> sentenceNamesDefined = Sets.newHashSet();
        sentenceNamesDefined.add(GdlPool.TRUE);
        sentenceNamesDefined.add(GdlPool.DOES);
        for (GdlRelation relation : relations) {
            sentenceNamesDefined.add(relation.getName());
        }
        for (GdlRule rule : rules) {
            sentenceNamesDefined.add(rule.getHead().getName());
        }
        Set<GdlConstant> sentenceNamesReferenced = Sets.newHashSet();
        for (GdlRule rule : rules) {
            List<GdlSentence> ruleBodySentences = Lists.newArrayList();
            for (GdlLiteral conjunct : rule.getBody()) {
                getSentencesInLiteral(conjunct, ruleBodySentences);
            }
            for (GdlSentence sentence : ruleBodySentences) {
                sentenceNamesReferenced.add(sentence.getName());
            }
        }
        Set<GdlConstant> missingNames = Sets.difference(sentenceNamesReferenced, sentenceNamesDefined);
        for (GdlConstant missingName : missingNames) {
            warnings.add(new ValidatorWarning("A rule references the sentence name " + missingName + ", but no sentence with that name is defined"));
        }
    }

    @Override
    public List<ValidatorWarning> checkValidity(Game theGame) throws ValidatorException {
        StaticValidator.matchParentheses(theGame.getRulesheet().split("[\r\n]"));
        return StaticValidator.validateDescription(theGame.getRules());
    }

    //These are test cases for smooth handling of errors that often
    //appear in rulesheets. They are intentionally invalid.
    private static final ImmutableSet<String> GAME_KEY_BLACKLIST =
            ImmutableSet.of("test_case_3b",
                    "test_case_3e",
                    "test_case_3f",
                    "test_invalid_function_arities_differ",
                    "test_invalid_sentence_arities_differ",
                    "test_clean_not_distinct");
    /**
     * Tries to test most of the rulesheets in the games directory. This should
     * be run when developing a new game to spot errors.
     */
    public static void main(String[] args) {
        GameRepository testGameRepo = new TestGameRepository();

        for(String gameKey : testGameRepo.getGameKeys()) {
            if (GAME_KEY_BLACKLIST.contains(gameKey)) {
                continue;
            }

            System.out.println("Testing " + gameKey);
            try {
                List<ValidatorWarning> warnings = new StaticValidator().checkValidity(testGameRepo.getGame(gameKey));
                if (!warnings.isEmpty()) {
                    System.out.println("Warnings for " + gameKey + ": " + warnings);
                }
            } catch (ValidatorException e) {
                e.printStackTrace();
                //Draw attention to the error
                return;
            }
        }
    }
}