/*
 * Copyright (C) 2018 Yaroslav Pronin <proninyaroslav@mail.ru>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.proninyaroslav.template;

import java.util.ArrayList;
import java.util.List;

import ru.proninyaroslav.template.exceptions.ParseException;

/**
 * Element in the parse tree
 */

abstract class Node {
    public final Type type;
    public int pos;
    protected Tree tree;

    public Node(final Tree tree, final Type type, final int pos) {
        this.tree = tree;
        this.type = type;
        this.pos = pos;
    }

    /**
     * Makes a deep copy of the node and all its components
     */
    public abstract Node copy();

    public enum Type {
        TEXT,           /* plain text */
        ACTION,         /* a non-control action such as a field evaluation */
        BOOL,
        CHAIN,          /* a sequence of field accesses */
        COMMAND,        /* An element of a pipeline */
        DOT,            /* the cursor, dot */
        FIELD,          /* a field or method name */
        IDENTIFIER,     /* an identifier; always a function name */
        IF,
        LIST,           /* a list of Nodes */
        NULL,
        NUMBER,
        PIPE,           /* a pipeline of commands */
        FOR,
        ELSE,           /* an else action. Not added to tree */
        END,            /* an end action. Not added to tree */
        WITH,
        BREAK,
        CONTINUE,
        STRING,
        TEMPLATE,       /* a template invocation action */
        VARIABLE
    }

    /**
     * Holds a sequence of nodes
     */
    public static class Sequence extends Node {
        public final List<Node> nodes;

        public Sequence(final Tree tree, final int pos) {
            super(tree, Type.LIST, pos);
            nodes = new ArrayList<>();
        }

        public void append(final Node node) {
            nodes.add(node);
        }

        public Sequence copyList() {
            final Sequence sequence = new Sequence(tree, pos);
            for (Node node : nodes) {
                sequence.append(node.copy());
            }

            return sequence;
        }

        @Override
        public Node copy() {
            return copyList();
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            for (Node node : nodes) {
                sb.append(node);
            }

            return sb.toString();
        }
    }

    /**
     * Holds a plain text
     */
    public static class Text extends Node {
        public final String text; /* may span newlines */

        public Text(final Tree tree, final int pos, final String text) {
            super(tree, Type.TEXT, pos);
            this.text = text;
        }

        @Override
        public Node copy() {
            return new Text(tree, pos, text);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * Holds a pipeline with optional declaration
     */
    public static class Pipe extends Node {
        public boolean decl;      /* the variables are being declared, not assigned */
        final List<Command> cmds;  /* the commands in lexical order */
        List<Assign> vars;   /* variables in lexical order */

        public Pipe(final Tree tree, final int pos, final java.util.List<Assign> vars) {
            super(tree, Type.PIPE, pos);
            this.vars = new ArrayList<>(vars);
            cmds = new ArrayList<>();
        }

        public void append(final Command cmd) {
            cmds.add(cmd);
        }

        public Pipe copyPipe() {
            final List<Assign> copyDecl = new ArrayList<>();
            for (final Assign d : vars) {
                copyDecl.add((Assign) d.copy());
            }
            final Pipe pipe = new Pipe(tree, pos, copyDecl);
            pipe.vars = new ArrayList<>(vars);
            for (final Command cmd : cmds) {
                pipe.append((Command) cmd.copy());
            }

            return pipe;
        }

        @Override
        public Node copy() {
            return copyPipe();
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();

            if (vars.size() == 1) {
                sb.append(vars.get(0));
                sb.append(" := ");
            }

            for (int i = 0; i < cmds.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(cmds.get(i));
            }

            return sb.toString();
        }
    }

    /**
     * Holds a list of variable names, possibly with chained field accesses.
     * The dollar sign is part of the (first) name
     */
    public static class Assign extends Node {
        public final List<String> ident; /* variable name and fields in lexical order */

        public Assign(final Tree tree, final int pos, final java.util.List<String> ident) {
            super(tree, Type.VARIABLE, pos);
            this.ident = new ArrayList<>(ident);
        }

        @Override
        public Node copy() {
            return new Assign(tree, pos, new ArrayList<>(ident));
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ident.size(); i++) {
                if (i > 0) {
                    sb.append(".");
                }
                sb.append(ident.get(i));
            }

            return sb.toString();
        }
    }

    /**
     * Holds a command (a pipeline inside an evaluating action)
     */
    public static class Command extends Node {
        public final List<Node> args; /* arguments in lexical order: identifier, field, or constant */

        public Command(final Tree tree, final int pos) {
            super(tree, Type.COMMAND, pos);
            args = new ArrayList<>();
        }

        public void append(Node node) {
            args.add(node);
        }

        @Override
        public Node copy() {
            final Command command = new Command(tree, pos);
            for (Node arg : args) {
                command.append(arg.copy());
            }

            return command;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    sb.append(" ");
                }
                final Node arg = args.get(i);
                if (arg.type == Type.PIPE) {
                    sb.append("(").append(arg).append(")");
                    continue;
                }
                sb.append(arg);
            }

            return sb.toString();
        }
    }

    /**
     * Holds an action (something bounded by delimiters).
     * Control actions have their own nodes; Action represents simple
     * ones such as field evaluations and parenthesized pipelines
     */
    public static class Action extends Node {
        public final Pipe pipe;

        public Action(final Tree tree, final int pos, final Pipe pipe) {
            super(tree, Type.ACTION, pos);
            this.pipe = pipe;
        }

        @Override
        public Node copy() {
            return new Action(tree, pos, pipe.copyPipe());
        }

        @Override
        public String toString() {
            return String.format("{{%s}}", pipe);
        }
    }

    public static class Identifier extends Node {
        public final String ident; /* the identifier's name */

        public Identifier(final Tree tree, final int pos, final String ident) {
            super(tree, Type.IDENTIFIER, pos);
            this.ident = ident;
        }

        @Override
        public Node copy() {
            return new Identifier(tree, pos, ident);
        }

        @Override
        public String toString() {
            return ident;
        }
    }

    /**
     * Holds the special identifier '.'
     */
    public static class Dot extends Node {
        public Dot(final Tree tree, final int pos) {
            super(tree, Type.DOT, pos);
        }

        @Override
        public Node copy() {
            return new Dot(tree, pos);
        }

        @Override
        public String toString() {
            return ".";
        }
    }

    public static class Null extends Node {
        public Null(final Tree tree, final int pos) {
            super(tree, Type.NULL, pos);
        }

        @Override
        public Node copy() {
            return new Null(tree, pos);
        }

        @Override
        public String toString() {
            return "null";
        }
    }

    /**
     * Holds a field (identifier starting with '.').
     * The names may be chained ('.x.y').
     * The dot is dropped from each ident
     */
    public static class Field extends Node {
        public final List<String> ident; /* variable name and fields in lexical order */

        public Field(final Tree tree, final int pos, final java.util.List<String> ident) {
            super(tree, Type.FIELD, pos);
            this.ident = new ArrayList<>(ident);
        }

        @Override
        public Node copy() {
            return new Field(tree, pos, new ArrayList<>(ident));
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            for (final String i : ident) {
                sb.append(".").append(i);
            }

            return sb.toString();
        }
    }

    /**
     * Holds a term followed by a chain of field accesses (identifier starting with '.').
     * The names may be chained ('.x.y'). The periods are dropped from each ident
     */
    public static class Chain extends Node {
        public final List<String> field; /* the identifiers in lexical order */
        public final Node node;

        public Chain(final Tree tree, final int pos, final Node node) {
            super(tree, Type.CHAIN, pos);
            this.node = node;
            this.field = new ArrayList<>();
        }

        public Chain(final Tree tree, final int pos, final Node node, final java.util.List<String> field) {
            super(tree, Type.CHAIN, pos);
            this.node = node;
            this.field = new ArrayList<>(field);
        }

        /*
         * Ddds the named field (which should start with a dot) to the end of the chain
         */
        public void add(String field) throws ParseException {
            if (field.length() == 0 || field.charAt(0) != '.') {
                throw new ParseException("no dot in field");
            }
            field = field.substring(1);
            if (field.equals("")) {
                throw new ParseException("no dot in field");
            }
            this.field.add(field);
        }

        @Override
        public Node copy() {
            return new Chain(tree, pos, node, new ArrayList<>(field));
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            if (node.type == Type.PIPE) {
                sb.append("(").append(node).append(")");
            } else {
                sb.append(node);
            }
            for (String f : field) {
                sb.append(".").append(f);
            }

            return sb.toString();
        }
    }

    public static class Bool extends Node {
        public final boolean boolVal;

        public Bool(final Tree tree, final int pos, final boolean boolVal) {
            super(tree, Type.BOOL, pos);
            this.boolVal = boolVal;
        }

        @Override
        public Node copy() {
            return new Bool(tree, pos, boolVal);
        }

        @Override
        public String toString() {
            return (boolVal ? "true" : "false");
        }
    }

    /**
     * Holds a number: integer or float.
     * The value is parsed and stored under
     * all Java types that can represent the value
     */
    public static class Number extends Node {
        public boolean isInt;
        public boolean isFloat;
        public int intVal;
        public double floatVal;
        public final String text; /* the original textual representation from the input */

        public Number(final Tree tree, final int pos, final String text) {
            super(tree, Type.NUMBER, pos);
            this.text = text;
        }

        public Number(final Number node) {
            super(node.tree, node.type, node.pos);
            this.text = node.text;
            this.isInt = node.isInt;
            this.isFloat = node.isFloat;
            this.intVal = node.intVal;
            this.floatVal = node.floatVal;
        }

        @Override
        public Node copy() {
            return new Number(this);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * Holds a string constant. The value has been unquoted
     */
    public static class StringConst extends Node {
        public final String quoted;   /* the original text of the string, with quotes */
        public final String text;     /* the string, after quote processing */

        public StringConst(final Tree tree, final int pos, final String quoted, final String text) {
            super(tree, Type.STRING, pos);
            this.quoted = quoted;
            this.text = text;
        }

        @Override
        public Node copy() {
            return new StringConst(tree, pos, quoted, text);
        }

        @Override
        public String toString() {
            return quoted;
        }
    }

    /**
     * Represents an {end} action.
     * It does not appear in the final runParser tree
     */
    public static class End extends Node {
        public End(final Tree tree, final int pos) {
            super(tree, Type.END, pos);
        }

        @Override
        public Node copy() {
            return new End(tree, pos);
        }

        @Override
        public String toString() {
            return "{{end}}";
        }
    }

    /**
     * Represents an {else} action.
     * It does not appear in the final runParser tree
     */
    public static class Else extends Node {
        public Else(final Tree tree, final int pos) {
            super(tree, Type.ELSE, pos);
        }

        @Override
        public Node copy() {
            return new Else(tree, pos);
        }

        @Override
        public String toString() {
            return "{{else}}";
        }
    }

    /**
     * The common representation of if, with and for
     */
    public static class Branch extends Node {
        final Pipe pipe;      /* the pipeline to be evaluated */
        final Sequence sequence;      /* what to execute if the value is non-empty */
        final Sequence elseSequence;  /* what to execute if the value is empty (null if absent) */

        public Branch(final Tree tree, final Type type, final int pos,
                      final Pipe pipe, final Sequence sequence, final Sequence elseSequence) {
            super(tree, type, pos);
            this.pipe = pipe;
            this.sequence = sequence;
            this.elseSequence = elseSequence;
        }

        @Override
        public Node copy() {
            switch (type) {
                case IF:
                    return new If(tree, pos, pipe,
                            sequence, elseSequence);
                case FOR:
                    return new For(tree, pos, pipe,
                            sequence, elseSequence);
                case WITH:
                    return new With(tree, pos, pipe,
                            sequence, elseSequence);
                default:
                    return null;
            }
        }

        @Override
        public String toString() {
            final String name;
            switch (type) {
                case IF:
                    name = "if";
                    break;
                case FOR:
                    name = "for";
                    break;
                case WITH:
                    name = "with";
                    break;
                default:
                    return "unknown branch type";
            }
            if (elseSequence != null) {
                return String.format("{{%s %s}}%s{{else}}%s{{end}}", name, pipe, sequence, elseSequence);
            }

            return String.format("{{%s %s}}%s{{end}}", name, pipe, sequence);
        }
    }

    public static class If extends Branch {
        public If(final Tree tree, final int pos, final Pipe pipe,
                  final Sequence sequence, final Sequence elseSequence) {
            super(tree, Type.IF, pos, pipe, sequence, elseSequence);
        }

        @Override
        public Node copy() {
            return new If(tree, pos, pipe.copyPipe(), sequence.copyList(),
                    elseSequence != null ? elseSequence.copyList() : null);
        }
    }

    public static class For extends Branch {
        public For(final Tree tree, final int pos, final Pipe pipe,
                   final Sequence sequence, final Sequence elseSequence) {
            super(tree, Type.FOR, pos, pipe, sequence, elseSequence);
        }

        @Override
        public Node copy() {
            return new For(tree, pos, pipe.copyPipe(), sequence.copyList(),
                    elseSequence != null ? elseSequence.copyList() : null);
        }
    }

    public static class With extends Branch {
        public With(final Tree tree, final int pos, final Pipe pipe,
                    final Sequence sequence, final Sequence elseSequence) {
            super(tree, Type.WITH, pos, pipe, sequence, elseSequence);
        }

        @Override
        public Node copy() {
            return new With(tree, pos, pipe.copyPipe(), sequence.copyList(),
                    elseSequence != null ? elseSequence.copyList() : null);
        }
    }

    public static class Break extends Node {
        public Break(final Tree tree, final int pos) {
            super(tree, Type.BREAK, pos);
        }

        @Override
        public Node copy() {
            return new Break(tree, pos);
        }

        @Override
        public String toString() {
            return "{{break}}";
        }
    }

    public static class Continue extends Node {
        public Continue(final Tree tree, final int pos) {
            super(tree, Type.CONTINUE, pos);
        }

        @Override
        public Node copy() {
            return new Continue(tree, pos);
        }

        @Override
        public String toString() {
            return "{{continue}}";
        }
    }

    /**
     * Represents a {template} action
     */
    public static class Template extends Node {
        public final String name;     /* the name of the template (unquoted) */
        public final Pipe pipe;       /* the command to evaluate as dot for the template */

        public Template(final Tree tree, final int pos, final String name, final Pipe pipe) {
            super(tree, Type.TEMPLATE, pos);
            this.name = name;
            this.pipe = pipe;
        }

        @Override
        public Node copy() {
            return new Template(tree, pos, name, pipe != null ? pipe.copyPipe() : null);
        }

        @Override
        public String toString() {
            if (pipe == null) {
                return String.format("{{template \"%s\"}}", name);
            }
            return String.format("{{template \"%s\" %s}}", name, pipe);
        }
    }
}
