package org.acme.wrapper.graph;

/**
 * Result of a next-node lookahead: the {@link NextNodeType} and (when HUMAN) the
 * name of the next human task.
 */
public record NextNode(NextNodeType type, String nodeName) {

    public static NextNode human(String nodeName) {
        return new NextNode(NextNodeType.HUMAN, nodeName);
    }

    public static NextNode end() {
        return new NextNode(NextNodeType.END, null);
    }

    public static NextNode unknown() {
        return new NextNode(NextNodeType.UNKNOWN, null);
    }
}
