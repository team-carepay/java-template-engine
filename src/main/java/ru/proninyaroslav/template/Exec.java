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

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import ru.proninyaroslav.template.exceptions.ExecException;

/**
 * Represents the state of an execution
 */

class Exec {
    private static final int maxExecDepth = 1500; /* Max nesting of templates */
    final PrintWriter pw;
    private Template tmpl;
    private Node node;                           /* current node, for errors */
    private List<Template.Variable> vars;   /* stack of variable values */
    private int depth;                           /* the height of the stack of executing templates */
    private int forDepth;                 /* nesting level of for loops */

    Exec(final Template tmpl, final PrintWriter pw, final List<Template.Variable> vars) {
        this.tmpl = tmpl;
        this.pw = pw;
        this.vars = vars;
    }

    private Exec(final Exec s) {
        this.tmpl = s.tmpl;
        this.pw = s.pw;
        this.node = s.node;
        this.vars = s.vars;
        this.depth = s.depth;
    }

    void errorf(String format, final Object... args) throws ExecException {
        final String name = Utils.doublePercent(tmpl.name);
        if (node == null) {
            format = String.format("template: %s: %s", name,
                    String.format(format, args));
        } else {
            String location = tmpl.tree.errorLocation(node);
            String context = tmpl.tree.errorContext(node);
            format = String.format("template: %s: executing %s at <%s>: %s",
                    location, name, Utils.doublePercent(context),
                    String.format(format, args));
        }

        throw new ExecException(format);
    }

    private void push(final String name, final Object value) {
        vars.add(new Template.Variable(name, value));
    }

    /**
     * Pops the variable stack up to the mark
     */
    private void pop(final int mark) {
        vars = new ArrayList<>(vars.subList(0, mark));
    }

    private int stackSize() {
        return vars.size();
    }

    /**
     * Overwrites the top-nth variable on the stack.
     * Used by range iterations
     */
    private void setTopVar(final int n, final Object value) {
        vars.get(vars.size() - n).value = value;
    }

    /**
     * Overwrites the last declared variable with the given name.
     * Used by variable assignments
     */
    private void setVar(final String name, final Object value) throws ExecException {
        for (int i = stackSize() - 1; i >= 0; i--) {
            if (vars.get(i).name.equals(name)) {
                vars.get(i).value = value;
                return;
            }
        }

        errorf("undefined variable: %s", name);
    }

    private Object varValue(final String name) throws ExecException {
        for (int i = vars.size() - 1; i >= 0; i--) {
            if (vars.get(i).name.equals(name)) {
                return vars.get(i).value;
            }
        }
        errorf("undefined variable: %s", name);

        return null;
    }

    /**
     * Marks the state to be on node, for error reporting
     */
    private void at(final Node node) {
        this.node = node;
    }

    private void printValue(final Object value) {
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            final Object[] arr = new Object[length];
            for (int i = 0; i < length; i++) {
                arr[i] = Array.get(value, i);
            }
            pw.print(Arrays.deepToString(arr));
        } else {
            pw.print(value);
        }
    }

    private void notAFunction(final List<Node> args, final Object finalVal) throws ExecException {
        if (args != null && (args.size() > 1 || finalVal != null)) {
            errorf("can't give argument to non-function %s", args.get(0));
        }
    }

    /**
     * Returns the value of a number in a context where we don't know the type
     * (If it was a method argument, we'd know what we need.)
     * The syntax guides us to some extent.
     */
    private Object constant(final Node.Number num) {
        at(num);
        if (num.isFloat && !Utils.isHexConstant(num.text) &&
                Utils.containsAny(num.text, ".eE")) {
            return num.floatVal;
        } else if (num.isInt) {
            return num.intVal;
        }

        return null;
    }

    ForControl walk(final Object dot, final Node node) throws ExecException {
        at(node);
        if (node instanceof Node.Action) {
            /* If the action declares variables, don't print the result */
            final Node.Action nodeAction = (Node.Action) node;
            final Object val = evalPipeline(dot, nodeAction.pipe);
            if (nodeAction.pipe.vars.size() == 0) {
                printValue(val);
            }
        } else if (node instanceof Node.If) {
            final Node.If nodeIf = (Node.If) node;
            return walkIfOrWith(Node.Type.IF, dot, nodeIf.pipe,
                    nodeIf.sequence, nodeIf.elseSequence);
        } else if (node instanceof Node.Sequence) {
            for (final Node n : ((Node.Sequence) node).nodes) {
                final ForControl c = walk(dot, n);
                if (c != ForControl.NONE) {
                    return c;
                }
            }
        } else if (node instanceof Node.For) {
            return walkFor(dot, (Node.For) node);
        } else if (node instanceof Node.Template) {
            walkTemplate(dot, (Node.Template) node);
        } else if (node instanceof Node.Text) {
            pw.write(((Node.Text) node).text);
        } else if (node instanceof Node.With) {
            final Node.With nodeWith = (Node.With) node;
            return walkIfOrWith(Node.Type.WITH, dot, nodeWith.pipe,
                    nodeWith.sequence, nodeWith.elseSequence);
        } else if (node instanceof Node.Break) {
            if (forDepth == 0) {
                errorf("invalid break outside of for");
            }
            return ForControl.BREAK;
        } else if (node instanceof Node.Continue) {
            if (forDepth == 0) {
                errorf("invalid continue outside of for");
            }
            return ForControl.CONTINUE;
        } else {
            errorf("unknown node: %s", node);
        }

        return ForControl.NONE;
    }

    /**
     * Walks an 'if' or 'with' node.
     * They are identical in behavior except that 'with' sets dot
     */
    private ForControl walkIfOrWith(final Node.Type type, final Object dot,
                                    final Node.Pipe pipe, final Node.Sequence sequence,
                                    final Node.Sequence elseSequence) throws ExecException {
        int stackSize = stackSize();
        try {
            final Object val = evalPipeline(dot, pipe);
            boolean truth = false;
            try {
                truth = Utils.isTrue(val);
            } catch (IllegalArgumentException e) {
                errorf("if/with can't use %s", val);
            }
            if (truth) {
                if (type == Node.Type.WITH) {
                    return walk(val, sequence);
                } else {
                    return walk(dot, sequence);
                }
            } else if (elseSequence != null) {
                return walk(dot, elseSequence);
            }
        } finally {
            pop(stackSize);
        }

        return ForControl.NONE;
    }

    private ForControl walkFor(final Object dot, final Node.For f) throws ExecException {
        at(f);
        final int stackSize = stackSize();
        try {
            Object val = evalPipeline(dot, f.pipe);
            int startStackSize = stackSize();
            ++forDepth;
            if (val != null) {
                if (val instanceof Iterable) {
                    final Iterator i = ((Iterable) val).iterator();
                    if (i.hasNext()) {
                        while (i.hasNext()) {
                            if (forIteration(f, i.next(),
                                    startStackSize) == ForControl.BREAK) {
                                break;
                            }
                        }
                        --forDepth;
                        return ForControl.NONE;
                    }
                } else if (val.getClass().isArray()) {
                    final int length = Array.getLength(val);
                    if (length > 0) {
                        for (int i = 0; i < length; i++) {
                            if (forIteration(f, Array.get(val, i),
                                    startStackSize) == ForControl.BREAK) {
                                break;
                            }
                        }
                        --forDepth;
                        return ForControl.NONE;
                    }
                } else {
                    errorf("for can't iterable over %s", val);
                }
            }
            --forDepth;
            if (f.elseSequence != null) {
                return walk(dot, f.elseSequence);
            }
        } finally {
            pop(stackSize);
        }

        return ForControl.NONE;
    }

    private ForControl forIteration(final Node.For f, final Object elem, final int startStackSize) throws ExecException {
        if (f.pipe.vars.size() == 1) {
            setTopVar(1, elem);
        }
        final ForControl c = walk(elem, f.sequence);
        pop(startStackSize);

        return c;
    }

    private void walkTemplate(Object dot, final Node.Template template) throws ExecException {
        at(template);
        final Template tmpl = this.tmpl.common.tmpl.get(template.name);
        if (tmpl == null) {
            errorf("template %s not defined", template.name);
            return;
        }
        if (depth == maxExecDepth) {
            errorf("exceeded maximum template depth (%d)", maxExecDepth);
        }

        /* Variables declared by the pipeline persist */
        dot = evalPipeline(dot, template.pipe);
        final Exec newState = new Exec(this);
        newState.depth++;
        newState.tmpl = tmpl;
        /* Template invocations inherit no variables */
        newState.vars = new ArrayList<>();
        newState.vars.add(new Template.Variable("$", dot));
        newState.walk(dot, tmpl.tree.root);
    }

    private Object evalPipeline(final Object dot, final Node.Pipe pipe) throws ExecException {
        if (pipe == null) {
            return null;
        }

        at(pipe);
        Object val = null;
        for (Node.Command cmd : pipe.cmds) {
            val = evalCommand(dot, cmd, val);
        }
        for (Node.Assign var : pipe.vars) {
            if (pipe.decl) {
                push(var.ident.get(0), val);
            } else {
                setVar(var.ident.get(0), val);
            }
        }

        return val;
    }

    private Object evalCommand(final Object dot, final Node.Command cmd, final Object finalVal) throws ExecException {
        final Node firstWord = cmd.args.get(0);
        if (firstWord instanceof Node.Field) {
            return evalFieldNode(dot, (Node.Field) firstWord,
                    cmd.args, finalVal);
        } else if (firstWord instanceof Node.Chain) {
            return evalChainNode(dot, (Node.Chain) firstWord,
                    cmd.args, finalVal);
        } else if (firstWord instanceof Node.Identifier) {
            return evalFunction(dot, (Node.Identifier) firstWord,
                    cmd, cmd.args, finalVal);
        } else if (firstWord instanceof Node.Pipe) {
            /*
             * Parenthesized pipeline. The arguments are all
             * inside the pipeline; finalValue is ignored
             */
            return evalPipeline(dot, (Node.Pipe) firstWord);
        } else if (firstWord instanceof Node.Assign) {
            return evalVariableNode(dot, (Node.Assign) firstWord,
                    cmd.args, finalVal);
        }

        at(firstWord);
        notAFunction(cmd.args, finalVal);
        if (firstWord instanceof Node.Bool) {
            return ((Node.Bool) firstWord).boolVal;
        } else if (firstWord instanceof Node.Dot) {
            return dot;
        } else if (firstWord instanceof Node.Null) {
            errorf("null is not a command");
        } else if (firstWord instanceof Node.Number) {
            return constant((Node.Number) firstWord);
        } else if (firstWord instanceof Node.StringConst) {
            return ((Node.StringConst) firstWord).text;
        }

        errorf("can't evaluate command %s", firstWord);

        return null;
    }

    private Object evalFieldNode(final Object dot, final Node.Field field,
                                 final List<Node> args, final Object finalVal) throws ExecException {
        at(field);
        return evalFieldChain(dot, dot, field, field.ident, args, finalVal);
    }

    private Object evalChainNode(final Object dot, final Node.Chain chain,
                                 final List<Node> args, final Object finalVal) throws ExecException {
        at(chain);
        if (chain == null) {
            errorf("indirection through explicit null in %s");
            return null;
        }
        if (chain.field.size() == 0) {
            errorf("internal error: no fields in evalChainNode");
            return null;
        }
        /* In case (pipe).field1.field2 eval the pipeline, then the fields */
        final Object pipe = evalArg(dot, chain.node);

        return evalFieldChain(dot, pipe, chain, chain.field, args, finalVal);
    }

    private Object evalArg(final Object dot, final Node node) throws ExecException {
        /* Type checking occurs during the method/function call */
        at(node);
        if (node instanceof Node.Dot) {
            return dot;
        } else if (node instanceof Node.Null) {
            return null;
        } else if (node instanceof Node.Field) {
            final List<Node> args = new ArrayList<>();
            args.add(node);
            return evalFieldNode(dot, (Node.Field) node, args, null);
        } else if (node instanceof Node.Assign) {
            return evalVariableNode(dot, (Node.Assign) node, null, null);
        } else if (node instanceof Node.Pipe) {
            return evalPipeline(dot, (Node.Pipe) node);
        } else if (node instanceof Node.Identifier) {
            return evalFunction(dot, (Node.Identifier) node, node, null, null);
        } else if (node instanceof Node.Chain) {
            return evalChainNode(dot, (Node.Chain) node, null, null);
        } else if (node instanceof Node.Bool) {
            return ((Node.Bool) node).boolVal;
        } else if (node instanceof Node.Number) {
            return constant((Node.Number) node);
        } else if (node instanceof Node.StringConst) {
            return ((Node.StringConst) node).text;
        }
        errorf("can't handle %s for arg", node);

        return null;
    }

    /**
     * Evaluates .x.y.z possibly followed by arguments.
     * dot is the environment in which to evaluate arguments,
     * while receiver is the value being walked along the chain
     */
    private Object evalFieldChain(final Object dot, Object receiver, final Node node,
                                  final List<String> ident, final List<Node> args,
                                  final Object finalVal) throws ExecException {
        final int n = ident.size();
        for (int i = 0; i < n - 1; i++) {
            receiver = evalField(dot, ident.get(i), node,
                    null, null, receiver);
        }
        /* If it's a method, it gets the arguments */
        return evalField(dot, ident.get(n - 1), node,
                args, finalVal, receiver);
    }

    /**
     * Evaluates an expression like .field or .field arg1 arg2.
     * The finalVal argument represents the return value from the
     * preceding value of the pipeline.
     * Field and method with one name are not allowed
     */
    private Object evalField(final Object dot, final String fieldName, final Node node,
                             final List<Node> args, final Object finalVal, final Object receiver) throws ExecException {
        if (receiver == null) {
            errorf("null pointer evaluating null.%s", fieldName);
            return null;
        }

        /* Special case of calling an array length field */
        if (receiver.getClass().isArray() && fieldName.equals("length")) {
            return Array.getLength(receiver);
        }

        if (receiver instanceof Map) {
            final Map map = (Map) receiver;
            if (map.containsKey(fieldName)) {
                return map.get(fieldName);
            }
        }

        Field field = null;
        boolean hasArgs = args != null && (args.size() > 1 || finalVal != null);

        // first try getter
        try {
            PropertyDescriptor pd = new PropertyDescriptor(fieldName, receiver.getClass());
            Method getter = pd.getReadMethod();
            if (getter != null) {
                return getter.invoke(receiver);
            }
        } catch (IntrospectionException | InvocationTargetException | IllegalAccessException e) {
            // ignore
        }

        /* Find methods */
        final List<Method> foundMethods = ClassUtils.findMethods(receiver.getClass(), fieldName);

        if (!foundMethods.isEmpty()) {
            return evalCall(dot, foundMethods, node, fieldName,
                    args, finalVal, receiver);

        }
        /* Find field */
        field = ClassUtils.findField(receiver.getClass(), fieldName);

        if (field != null && !foundMethods.isEmpty()) {
            errorf("type %s has both field and method named %s",
                    receiver.getClass().toString(), fieldName);
            return null;
        }

        if (field != null) {
            if (hasArgs) {
                errorf("%s has arguments but cannot be invoked as method", fieldName);
                return null;
            }
            try {
                field.setAccessible(true);
                return field.get(receiver);

            } catch (IllegalAccessException e) {
                errorf("%s is a non-public field of class %s",
                        fieldName, receiver.getClass().getName());
            } catch (IllegalArgumentException e) {
                errorf("can't evaluate field %s in class %s",
                        fieldName, receiver.getClass().getName());
            }
        }

        return null;
    }

    private Object evalFunction(final Object dot, final Node.Identifier node,
                                final Node cmd, final List<Node> args, final Object finalVal) throws ExecException {
        final String name = node.ident;
        final List<Method> func = tmpl.findFunc(name);
        if (func == null) {
            errorf("%s is not a defined function", name);
            return null;
        }

        return evalCall(dot, func, cmd, name, args, finalVal, null);
    }

    /**
     * Executes method or function call.
     * It takes as an argument an array of functions, since they can be overridden
     */
    private Object evalCall(final Object dot, final List<Method> func, final Node node,
                            final String name, List<Node> args, final Object finalVal,
                            final Object receiver) throws ExecException {
        /* Zeroth arg is function name/node; not passed to function*/
        if (args != null) {
            args = new ArrayList<>(args.subList(1, args.size()));
        }

        final int numArgs = (args != null ? args.size() : 0);
        final List<Object> argv = new ArrayList<>();
        /* Add object that calling method (or not if method is static)*/
        if (receiver != null) {
            argv.add(receiver);
        }
        for (int i = 0; i < numArgs; i++) {
            argv.add(evalArg(dot, args.get(i)));
        }
        /* Add final value if necessary */
        if (finalVal != null) {
            argv.add(finalVal);
        }

        Object result = null;
        final List<String> err = new ArrayList<>();
        final String errFmt = "\n(%s): %s";
        /* Try to call method */
        for (Method m : func) {
            if (m.getReturnType() == void.class) {
                err.add(String.format(errFmt, m,
                        "can't call method/function with void return type"));
                continue;
            }
            try {
                MethodHandle mh = MethodHandles.lookup().unreflect(m);
                result = mh.invokeWithArguments(argv);
            } catch (Throwable e) {
                if (e instanceof NullPointerException)
                    err.add(String.format(errFmt, m, "assign null to primitive type"));
                else
                    err.add(String.format(errFmt, m, e));
            }
        }

        if (result == null && !err.isEmpty()) {
            at(node);
            StringBuilder sb = new StringBuilder("error calling " + name + ":");
            for (String e : err) {
                sb.append(e);
            }
            errorf(sb.toString());
        }

        return result;
    }

    private Object evalVariableNode(final Object dot, final Node.Assign var,
                                    final List<Node> args, final Object finalVal) throws ExecException {
        /*
         * $x.field has $x as the first ident, field as the second.
         * Eval the var, then the fields
         */
        at(var);
        final Object val = varValue(var.ident.get(0));
        final int size = var.ident.size();
        if (size == 1) {
            notAFunction(args, finalVal);
            return val;
        }
        return evalFieldChain(dot, val, var, var.ident.subList(1, size),
                args, finalVal);
    }

    enum ForControl {
        NONE,        /* no action */
        BREAK,        /* break out of for */
        CONTINUE    /* continues next for iteration */
    }
}
