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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ru.proninyaroslav.template.exceptions.InternalException;
import ru.proninyaroslav.template.exceptions.ParseException;

/**
 * Representation of a single parsed template
 */

public class Tree {
    private final Token[] token = new Token[3];   /* three-token lookahead for parser */
    public String name;                     /* template name */
    public String parseName;                /* name of the top-level template during parsing, for error messages */
    public Node.Sequence root;                  /* top-level root of the tree */
    private String text;                    /* text parsed to create the template (or its parent) */
    /* Parsing only; cleared after runParser */
    private Lexer lex;
    private int peekCount;
    private int forDepth;            /* nesting level of for loops */
    private FuncMap[] funcs;
    private List<String> vars;         /* variables defined at the moment */
    private Map<String, Tree> treeSet;

    public Tree(final String name, final String parseName,
                final String text, final FuncMap... funcs) {
        this.name = name;
        this.parseName = parseName;
        this.text = text;
        this.funcs = funcs;
    }

    public Tree(final String name, final FuncMap... funcs) {
        this.name = name;
        this.funcs = funcs;
    }

    public static Map<String, Tree> parse(final String name, final String text,
                                          final String leftDelim, final String rightDelim,
                                          final FuncMap... funcs) throws ParseException, InternalException {
        final Map<String, Tree> treeSet = new HashMap<>();
        final Tree tree = new Tree(name);
        tree.text = text;
        tree.parse(text, leftDelim, rightDelim, treeSet, funcs);

        return treeSet;
    }

    /**
     * Reports whether this tree (node) is empty of everything but space
     */
    public static boolean isEmptyTree(final Node node) throws ParseException {
        if (node == null) {
            return true;
        }

        if (node instanceof Node.Sequence) {
            for (Node n : ((Node.Sequence) node).nodes) {
                if (isEmptyTree(n)) {
                    return false;
                }
            }
            return true;
        } else if (node instanceof Node.Text) {
            return ((Node.Text) node).text.trim().length() == 0;
        } else if (!(node instanceof Node.Action) &&
                !(node instanceof Node.If) &&
                !(node instanceof Node.For) &&
                !(node instanceof Node.Template) &&
                !(node instanceof Node.With)) {
            throw new ParseException(String.format("unknown node: %s", node));
        }

        return false;
    }

    private void parse(final String text, final String leftDelim, final String rightDelim,
                       final Map<String, Tree> treeSet, final FuncMap... funcs) throws ParseException, InternalException {
        try {
            this.parseName = name;
            startParse(funcs, new Lexer(name, text, leftDelim, rightDelim), treeSet);
            this.text = text;
            parse();
            add();
        } finally {
            this.lex.drain();
            stopParse();
        }
    }

    private void startParse(final FuncMap[] funcs, final Lexer lex, final Map<String, Tree> treeSet) {
        this.lex = lex;
        this.funcs = funcs;
        this.treeSet = treeSet;
        this.vars = new ArrayList<>();
        this.vars.add("$");
    }

    private void stopParse() {
        this.lex = null;
        this.vars = null;
        this.funcs = null;
        this.treeSet = null;
        this.forDepth = 0;
    }

    /**
     * Adds tree to treeSet
     */
    private void add() throws ParseException {
        final Tree tree = treeSet.get(name);
        if (tree == null || isEmptyTree(root)) {
            treeSet.put(name, this);
            return;
        }

        if (!isEmptyTree(root))
            errorf("template: multiple definition of template %s", name);
    }

    public String errorLocation(final Node node) {
        Tree tree = node.tree;
        if (tree == null) {
            tree = this;
        }
        final String text = tree.text.substring(0, node.pos);
        int index = text.lastIndexOf('\n');
        if (index == -1) {
            index = node.pos; /* On first line */
        } else {
            ++index; /* After newline */
            index = node.pos = index;
        }
        final int lineNum = Utils.countChars(text, '\n');

        return String.format("%s:%d:%d", tree.parseName, lineNum, index);
    }

    public String errorContext(final Node node) {
        String context = node.toString();
        if (context.length() > 20) {
            context = String.format("%.20s...", context);
        }

        return context;
    }

    private void errorf(final String format, final Object... args) throws ParseException {
        this.root = null;
        throw new ParseException(
                String.format(String.format("%s:%d: %s", parseName, token[0].line, format),
                        args));
    }

    /**
     * Backs the input stream up one token
     */
    private void backup() {
        ++peekCount;
    }

    /**
     * Backs the input stream up two tokens.
     * The zeroth token is already there
     */
    private void backupTwo(final Token t) {
        token[1] = t;
        peekCount = 2;
    }

    /**
     * Backs the input stream up three tokens.
     * The zeroth token is already there
     */
    private void backupThree(final Token t2, final Token t1) /* Reverse order: we're pushing back */ {
        token[1] = t1;
        token[2] = t2;
        peekCount = 3;
    }

    /**
     * Returns but does not consume the next token
     */
    private Token peek() throws InternalException {
        if (peekCount > 0) {
            return token[peekCount - 1];
        }
        peekCount = 1;
        token[0] = lex.nextToken();

        return token[0];
    }

    private Token peekNonSpace() throws InternalException {
        Token token = next();
        for (; ; ) {
            if (token.type != Token.Type.SPACE) {
                break;
            }
            token = next();
        }
        backup();

        return token;
    }

    private Token next() throws InternalException {
        if (peekCount > 0) {
            --peekCount;
        } else {
            token[0] = lex.nextToken();
        }
        return token[peekCount];
    }

    private Token nextNonSpace() throws InternalException {
        Token token = next();
        for (; ; ) {
            if (token.type != Token.Type.SPACE) {
                break;
            }
            token = next();
        }

        return token;
    }

    /**
     * Consumes the next token and guarantees it has the required type
     */
    private Token expect(final Token.Type expected, final String context) throws ParseException, InternalException {
        final Token token = nextNonSpace();
        if (token.type != expected) {
            unexpected(token, context);
        }

        return token;
    }

    /**
     * Consumes the next token and guarantees it has one of the required types
     */
    private Token expectOneOf(final Token.Type expected1,
                              final Token.Type expected2,
                              final String context) throws ParseException, InternalException {
        final Token token = nextNonSpace();
        if (token.type != expected1 && token.type != expected2) {
            unexpected(token, context);
        }

        return token;
    }

    private void unexpected(final Token token, final String context) throws ParseException {
        errorf("unexpected %s in %s", token, context);
    }

    private boolean hasFunction(final String name) {
        for (final FuncMap funcMap : funcs) {
            if (funcMap == null) {
                continue;
            }
            if (funcMap.contains(name)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns a node for a variable reference.
     * It errors if the variable is not defined
     */
    private Node useVar(final int pos, final String name) throws ParseException {
        final Node.Assign var = newVariable(pos, name);
        for (final String varName : vars) {
            if (var.ident.get(0).equals(varName)) {
                return var;
            }
        }
        errorf("undefined variable %s", name);

        return null;
    }

    /**
     * Trims the variable list to the specified length
     */
    private void popVars(final int n) {
        vars = new ArrayList<>(vars.subList(0, n));
    }

    private void parse() throws ParseException, InternalException {
        root = newList(peek().pos);
        while (peek().type != Token.Type.EOF) {
            if (peek().type == Token.Type.LEFT_DELIM) {
                final Token delim = next();
                if (nextNonSpace().type == Token.Type.DEFINE) {
                    /* Name will be updated once we know it */
                    final Tree newTree = new Tree("definition", parseName, text);
                    newTree.startParse(funcs, lex, treeSet);
                    newTree.parseDefinition();
                    continue;
                }
                backupTwo(delim);
            }
            final Node n = textOrAction();
            if (n == null || n.type == Node.Type.END ||
                    n.type == Node.Type.ELSE) {
                errorf("unexpected %s", n);
            } else {
                root.append(n);
            }
        }
    }

    /**
     * Parses a {define} ... {end} template definition and
     * installs the definition in treeSet.
     * The "define" keyword has already been scanned
     */
    private void parseDefinition() throws ParseException, InternalException {
        final String context = "define clause";
        final Token name = expectOneOf(Token.Type.STRING, Token.Type.RAW_STRING, context);
        try {
            this.name = Utils.unquote(name.val);
        } catch (IllegalArgumentException e) {
            errorf("%s", e.getMessage());
        }
        expect(Token.Type.RIGHT_DELIM, context);

        final Node.Sequence[] outRoot = new Node.Sequence[1];
        final Node[] outEnd = new Node[1];
        tokenList(outRoot, outEnd);
        root = outRoot[0];
        if (outEnd[0].type != Node.Type.END) {
            errorf("unexpected %s in %s", outEnd[0], context);
        }
        add();
        stopParse();
    }

    /**
     * tokenList:
     * textOrAction*
     * Terminates at {end} or {else}, returned separately
     */
    private void tokenList(final Node.Sequence[] outSequence, final Node[] outNode) throws ParseException, InternalException {
        outSequence[0] = newList(peekNonSpace().pos);
        while (peekNonSpace().type != Token.Type.EOF) {
            outNode[0] = textOrAction();
            if (outNode[0] != null &&
                    (outNode[0].type == Node.Type.END ||
                            outNode[0].type == Node.Type.ELSE)) {
                return;
            }
            outSequence[0].append(outNode[0]);
        }
        errorf("unexpected EOF");
    }

    /**
     * textOrAction:
     * text | action
     */
    private Node textOrAction() throws ParseException, InternalException {
        final Token token = nextNonSpace();
        switch (token.type) {
            case TEXT:
                return newText(token.pos, token.val);
            case LEFT_DELIM:
                return action();
            default:
                unexpected(token, "input");
        }

        return null;
    }

    /**
     * action:
     * control
     * pipeline
     * Left delim is past. Now get actions.
     * First word could be a keyword such as for
     */
    private Node action() throws ParseException, InternalException {
        Token token = nextNonSpace();
        switch (token.type) {
            case ELSE:
                return elseControl();
            case END:
                return endControl();
            case IF:
                return ifControl();
            case FOR:
                return forControl();
            case TEMPLATE:
                return templateControl();
            case WITH:
                return withControl();
            case BREAK:
                return breakControl();
            case CONTINUE:
                return continueControl();
        }
        backup();
        token = peek();

        /* Do not pop variables; they persist until "end" */
        return newAction(token.pos, pipeline("command"));
    }

    /**
     * pipeline:
     * declaration? command ('|' command)*
     */
    private Node.Pipe pipeline(final String context) throws ParseException, InternalException {
        final List<Node.Assign> vars = new ArrayList<>();
        boolean decl = false;
        final int pos = peekNonSpace().pos;
        final Token v = peekNonSpace();
        if (v.type == Token.Type.VARIABLE) {
            next();
            /*
             * Since space is a token, we need 3-token look-ahead here in
             * the worst case: in "$x foo" we need to read "foo" (as opposed to "=")
             * to know that $x is an argument variable rather than a declaration.
             */
            final Token tokenAfterVariable = peek();
            final Token next = peekNonSpace();
            if (next.type == Token.Type.ASSIGN || next.type == Token.Type.DECLARE) {
                nextNonSpace();
                vars.add(newVariable(v.pos, v.val));
                this.vars.add(v.val);
                decl = next.type == Token.Type.DECLARE;
            } else if (tokenAfterVariable.type == Token.Type.SPACE) {
                backupThree(v, tokenAfterVariable);
            } else {
                backupTwo(v);
            }
        }

        final Node.Pipe pipe = newPipeline(pos, vars);
        pipe.decl = decl;
        for (; ; ) {
            final Token token = nextNonSpace();
            switch (token.type) {
                case RIGHT_DELIM:
                case RIGHT_PAREN:
                    checkPipeline(pipe, context);
                    if (token.type == Token.Type.RIGHT_PAREN)
                        backup();
                    return pipe;
                case BOOL:
                case CHAR_CONSTANT:
                case DOT:
                case FIELD:
                case IDENTIFIER:
                case NUMBER:
                case NULL:
                case STRING:
                case RAW_STRING:
                case VARIABLE:
                case LEFT_PAREN:
                    backup();
                    pipe.append(command());
                    break;
                default:
                    unexpected(token, context);
                    break;
            }
        }
    }

    private void checkPipeline(final Node.Pipe pipe, final String context) throws ParseException {
        /* Reject empty pipelines */
        if (pipe.cmds.size() == 0) {
            errorf("missing value for %s", context);
        }
        /* Only the first command of a pipeline can start with a non-executable operand */
        for (int i = 1; i < pipe.cmds.size(); i++) {
            final Node.Command c = pipe.cmds.get(i);
            switch (c.args.get(0).type) {
                case BOOL:
                case DOT:
                case NULL:
                case NUMBER:
                case STRING:
                    errorf("non executable command in pipeline stage %d", i + 1);
            }
        }
    }

    /**
     * command:
     * operand (space operand)*
     * Space-separated arguments up to a pipeline character or right delimiter.
     * We consume the pipe character but leave the right delim to terminate the action
     */
    private Node.Command command() throws ParseException, InternalException {
        final Node.Command cmd = newCommand(peekNonSpace().pos);
        for (; ; ) {
            /* Skip leading spaces */
            peekNonSpace();
            final Node operand = operand();
            if (operand != null) {
                cmd.append(operand);
            }
            final Token token = next();
            switch (token.type) {
                case SPACE:
                    continue;
                case ERROR:
                    errorf("%s", token.val);
                    break;
                case RIGHT_DELIM:
                case RIGHT_PAREN:
                    backup();
                    break;
                case PIPE:
                    break;
                default:
                    errorf("unexpected %s in operand", token);
            }
            break;
        }
        if (cmd.args.size() == 0) {
            errorf("empty command");
        }

        return cmd;
    }

    /**
     * operand:
     * term .field*
     * An operand is a space-separated component of a command,
     * a term possibly followed by field accesses.
     * A null return means the next token is not an operand
     */
    private Node operand() throws ParseException, InternalException {
        Node node = term();
        if (node == null) {
            return null;
        }
        if (peek().type == Token.Type.FIELD) {
            final Node.Chain chain = newChain(peek().pos, node);
            while (peek().type == Token.Type.FIELD) {
                chain.add(next().val);
            }
            /*
             * Obvious parsing errors involving literal values are detected here.
             * More complex error cases will have to be handled at execution time.
             */
            switch (node.type) {
                case FIELD:
                    node = newField(chain.pos, chain.toString());
                    break;
                case VARIABLE:
                    node = newVariable(chain.pos, chain.toString());
                    break;
                case BOOL:
                case NULL:
                case NUMBER:
                case DOT:
                    errorf("unexpected . after term %s", node);
                default:
                    node = chain;
            }
        }

        return node;
    }

    /**
     * term:
     * literal (number, string, null, boolean)
     * function (identifier)
     * dot
     * .field
     * $variable
     * '(' pipeline ')'
     * A term is a simple "expression".
     * A null return means the next item is not a term
     */
    private Node term() throws ParseException, InternalException {
        final Token token = nextNonSpace();
        switch (token.type) {
            case ERROR:
                errorf("%s", token.val);
            case IDENTIFIER:
                if (!hasFunction(token.val))
                    errorf("function '%s' not defined", token.val);
                final Node.Identifier i = newIdentifier(token.pos, token.val);
                i.tree = this;
                return i;
            case DOT:
                return newDot(token.pos);
            case NULL:
                return newNull(token.pos);
            case VARIABLE:
                return useVar(token.pos, token.val);
            case FIELD:
                return newField(token.pos, token.val);
            case BOOL:
                return newBool(token.pos, token.val.equals("true"));
            case CHAR_CONSTANT:
            case NUMBER:
                return newNumber(token.pos, token.val, token.type);
            case LEFT_PAREN:
                final Node.Pipe pipe = pipeline("parenthesized pipeline");
                final Token t = next();
                if (t.type != Token.Type.RIGHT_PAREN) {
                    errorf("unclosed right paren: unexpected %s", token);
                }
                return pipe;
            case STRING:
            case RAW_STRING:
                String s;
                try {
                    s = Utils.unquote(token.val);
                } catch (IllegalArgumentException e) {
                    throw new ParseException(e.getMessage());
                }
                return newString(token.pos, token.val, s);
        }
        backup();

        return null;
    }

    /**
     * else:
     * {{else}}
     */
    private Node elseControl() throws ParseException, InternalException {
        /* Special case for "else if" */
        final Token peek = peekNonSpace();
        if (peek.type == Token.Type.IF) {
            /* We see "{else if ... " but in effect rewrite it to {else}{if ... " */
            return newElse(peek.pos);
        }

        return newElse(expect(Token.Type.RIGHT_DELIM, "else").pos);
    }

    /**
     * end:
     * {{end}}
     */
    private Node endControl() throws ParseException, InternalException {
        return newEnd(expect(Token.Type.RIGHT_DELIM, "end").pos);
    }

    /**
     * template:
     * {{template stringValue pipeline}}
     * The name must be something that can evaluate to a string
     */
    private Node templateControl() throws ParseException, InternalException {
        final String context = "template clause";
        final Token token = nextNonSpace();
        final String name = parseTemplateName(token, context);
        Node.Pipe pipe = null;
        if (nextNonSpace().type != Token.Type.RIGHT_DELIM) {
            backup();
            /* Don't pop variables; they persist until "end" */
            pipe = pipeline(context);
        }

        return newTemplate(token.pos, name, pipe);
    }

    private String parseTemplateName(Token token, String context) throws ParseException {
        String name = "";
        if (token.type == Token.Type.STRING || token.type == Token.Type.RAW_STRING) {
            name = Utils.unquote(token.val);
        } else {
            unexpected(token, context);
        }

        return name;
    }

    /**
     * if:
     * {{if pipeline}} tokenList {{end}}
     * {{if pipeline}} tokenList {{else}} tokenList {{end}}
     */
    private Node ifControl() throws ParseException, InternalException {
        final int[] outPos = new int[1];
        final Node.Pipe[] outPipe = new Node.Pipe[1];
        final Node.Sequence[] outSequence = new Node.Sequence[1];
        final Node.Sequence[] outElseSequence = new Node.Sequence[1];
        parseControl(true, "if", outPos, outPipe, outSequence, outElseSequence);

        return newIf(outPos[0], outPipe[0], outSequence[0], outElseSequence[0]);
    }

    /**
     * for:
     * {{for pipeline}} tokenList {{end}}
     * {{for pipeline}} tokenList {{else}} tokenList {{end}}
     */
    private Node forControl() throws ParseException, InternalException {
        final int[] outPos = new int[1];
        final Node.Pipe[] outPipe = new Node.Pipe[1];
        final Node.Sequence[] outSequence = new Node.Sequence[1];
        final Node.Sequence[] outElseSequence = new Node.Sequence[1];
        parseControl(false, "for", outPos, outPipe, outSequence, outElseSequence);

        return newFor(outPos[0], outPipe[0], outSequence[0], outElseSequence[0]);
    }

    /**
     * break:
     * {{break}}
     */
    private Node breakControl() throws ParseException, InternalException {
        if (forDepth == 0) {
            errorf("unexpected break outside of for");
        }

        return newBreak(expect(Token.Type.RIGHT_DELIM, "break").pos);
    }

    /**
     * continue:
     * {{continue}}
     */
    private Node continueControl() throws ParseException, InternalException {
        if (forDepth == 0) {
            errorf("unexpected continue outside of for");
        }

        return newContinue(expect(Token.Type.RIGHT_DELIM, "continue").pos);
    }

    /**
     * with:
     * {{with pipeline}} tokenList {{end}}
     * {{with pipeline}} tokenList {{else}} tokenList {{end}}
     */
    private Node withControl() throws ParseException, InternalException {
        final int[] outPos = new int[1];
        final Node.Pipe[] outPipe = new Node.Pipe[1];
        final Node.Sequence[] outSequence = new Node.Sequence[1];
        final Node.Sequence[] outElseSequence = new Node.Sequence[1];
        parseControl(false, "with", outPos, outPipe, outSequence, outElseSequence);

        return newWith(outPos[0], outPipe[0], outSequence[0], outElseSequence[0]);
    }

    private void parseControl(final boolean allowElseIf, final String context,
                              final int[] outPos, final Node.Pipe[] outPipe,
                              final Node.Sequence[] outSequence, final Node.Sequence[] outElseSequence) throws ParseException, InternalException {
        final int varsSize = vars.size();
        try {
            outPipe[0] = pipeline(context);
            final Node[] next = new Node[1];
            if (context.equals("for")) {
                ++forDepth;
            }
            tokenList(outSequence, next);
            if (context.equals("for")) {
                --forDepth;
            }

            if (next[0].type == Node.Type.ELSE) {
                if (allowElseIf && peek().type == Token.Type.IF) {
                    /*
                     * Special case for "else if". If the "else" is followed immediately by an "if",
                     * the elseControl will have left the "if" token pending. Treat
                     *  {if a} {else if b} {end}
                     * as
                     *  {if a} {else}{if b} {end}{end}.
                     *  To do this, runParser the "if" as usual and stop at it {end}; the subsequent {end}
                     *  is assumed. This technique works even for long if-else-if chains
                     */
                    /* Consume the "if" token */
                    next();
                    outElseSequence[0] = newList(next[0].pos);
                    outElseSequence[0].append(ifControl());

                } else { /* Don't consume the next item - only one {end} required */
                    tokenList(outElseSequence, next);
                    if (next[0].type != Node.Type.END)
                        errorf("expected end; found %s", next[0]);
                }
            }
        } finally {
            popVars(varsSize);
        }
        outPos[0] = outPipe[0].pos;
    }

    Node.Sequence newList(final int pos) {
        return new Node.Sequence(this, pos);
    }

    Node.Text newText(final int pos, final String text) {
        return new Node.Text(this, pos, text);
    }

    Node.Pipe newPipeline(final int pos, final List<Node.Assign> decl) {
        return new Node.Pipe(this, pos, decl);
    }

    Node.Assign newVariable(final int pos, final String ident) {
        return new Node.Assign(this, pos, Arrays.asList(ident.split("\\.")));
    }

    Node.Command newCommand(final int pos) {
        return new Node.Command(this, pos);
    }

    Node.Action newAction(final int pos, final Node.Pipe pipe) {
        return new Node.Action(this, pos, pipe);
    }

    Node.Identifier newIdentifier(final int pos, final String ident) {
        return new Node.Identifier(this, pos, ident);
    }

    Node.Dot newDot(final int pos) {
        return new Node.Dot(this, pos);
    }

    Node.Null newNull(final int pos) {
        return new Node.Null(this, pos);
    }

    Node.Field newField(final int pos, final String ident) {
        /* substring(1) to drop leading dot */
        return new Node.Field(this, pos, Arrays.asList(ident.substring(1).split("\\.")));
    }

    Node.Chain newChain(final int pos, final Node node) {
        return new Node.Chain(this, pos, node);
    }

    Node.Bool newBool(final int pos, final boolean boolVal) {
        return new Node.Bool(this, pos, boolVal);
    }

    Node.Number newNumber(final int pos, final String text, final Token.Type type) throws ParseException {
        final Node.Number n = new Node.Number(this, pos, text);
        if (type == Token.Type.CHAR_CONSTANT) {
            char c;
            final StringBuilder tail = new StringBuilder();
            try {
                c = Utils.unquoteChar(text.substring(1),
                        text.charAt(0), tail);
            } catch (IllegalArgumentException e) {
                throw new ParseException(e);
            }
            if (!tail.toString().equals("'")) {
                throw new ParseException(String.format("malformed character constant: %s", text));
            }
            n.isInt = true;
            n.intVal = c;
            n.isFloat = true;
            n.floatVal = c;

            return n;
        }

        final boolean isNegative = text.startsWith("-");
        /* Trim leading sign */
        String unsignedNum;
        if (isNegative || text.startsWith("+")) {
            unsignedNum = text.substring(1);
        } else {
            unsignedNum = text;
        }
        if (!isNegative && unsignedNum.startsWith("-")) {
            throw new ParseException(String.format("illegal number syntax: %s", text));
        }
        try {
            long i; /* This is long for int overflow detection */
            if (unsignedNum.toLowerCase().startsWith("0x")) {  /* Is hex */
                /* Ignore leading 0x */
                i = Long.parseLong(unsignedNum.substring(2), 16);
            } else if (text.charAt(0) == '0') { /* Is octal */
                i = Long.parseLong(unsignedNum, 8);
            } else {
                i = Long.parseLong(unsignedNum);
            }
            if (i > Integer.MAX_VALUE || i < Integer.MIN_VALUE) {
                throw new ParseException(String.format("integer overflow: %s", text));
            }
            n.isInt = true;
            n.intVal = (int) i;
        } catch (NumberFormatException e) {
            /* Ignore */
        }

        /* If an integer extraction succeeded, promote the float */
        if (n.isInt) {
            n.isFloat = true;
            n.floatVal = n.intVal;
        } else {
            try {
                final double f = Double.parseDouble(unsignedNum);
                /*
                 * If we parsed it as a float, but it
                 * looks like an integer, it's a huge number
                 * too large to fit in a long. Reject it
                 */
                if (!Utils.containsAny(unsignedNum, ".eE")) {
                    throw new ParseException(String.format("integer overflow: %s", text));
                }
                n.isFloat = true;
                n.floatVal = f;
                /*
                 * if a floating-point extraction succeeded,
                 * extract the int if needed
                 */
                if (!n.isInt && (double) (int) f == f) {
                    n.isInt = true;
                    n.intVal = (int) f;
                }
            } catch (NumberFormatException e) {
                /* Ignore */
            }
        }
        if (isNegative) {
            n.intVal = -n.intVal;
            n.floatVal = -n.floatVal;
        }

        if (!n.isInt && !n.isFloat) {
            throw new ParseException(String.format("illegal number syntax: %s", text));
        }

        return n;
    }

    Node.StringConst newString(final int pos, final String orig, final String text) {
        return new Node.StringConst(this, pos, orig, text);
    }

    Node.End newEnd(final int pos) {
        return new Node.End(this, pos);
    }

    Node.Else newElse(final int pos) {
        return new Node.Else(this, pos);
    }

    Node.If newIf(final int pos, final Node.Pipe pipe,
                  final Node.Sequence sequence, final Node.Sequence elseSequence) {
        return new Node.If(this, pos, pipe, sequence, elseSequence);
    }

    Node.For newFor(final int pos, final Node.Pipe pipe,
                    final Node.Sequence sequence, final Node.Sequence elseSequence) {
        return new Node.For(this, pos, pipe, sequence, elseSequence);
    }

    Node.Break newBreak(final int pos) {
        return new Node.Break(this, pos);
    }

    Node.Continue newContinue(final int pos) {
        return new Node.Continue(this, pos);
    }

    Node.With newWith(final int pos, final Node.Pipe pipe,
                      final Node.Sequence sequence, final Node.Sequence elseSequence) {
        return new Node.With(this, pos, pipe, sequence, elseSequence);
    }

    Node.Template newTemplate(final int pos, final String name, final Node.Pipe pipe) {
        return new Node.Template(this, pos, name, pipe);
    }
}

