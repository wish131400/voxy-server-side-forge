package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.Map;

/** Mutation-safe round-robin cursor without copying the entire resident key set. */
final class PredictionMaintenanceCursor<K> {
    private static final class Node<K> {
        final K key;
        Node<K> previous, next;
        Node(K key) { this.key = key; }
    }
    private final Map<K, Node<K>> nodes = new HashMap<>();
    private Node<K> cursor;
    int size() { return nodes.size(); }
    void add(K key) {
        if (nodes.containsKey(key)) return;
        var node = new Node<>(key); nodes.put(key, node);
        if (cursor == null) { node.next = node.previous = node; cursor = node; }
        else {
            node.previous = cursor.previous; node.next = cursor;
            cursor.previous.next = node; cursor.previous = node;
        }
    }
    void remove(K key) {
        var node = nodes.remove(key);
        if (node == null) return;
        if (node.next == node) cursor = null;
        else {
            node.previous.next = node.next; node.next.previous = node.previous;
            if (cursor == node) cursor = node.next;
        }
    }
    K next() {
        if (cursor == null) return null;
        var key = cursor.key; cursor = cursor.next; return key;
    }
    void clear() { nodes.clear(); cursor = null; }
}
