function literalChildren (packet, label) {
  return literalChildrenAt(packet, [label])
}

function literalChildrenAt (packet, path) {
  const nodes = packet?.nodes
  if (!Array.isArray(nodes) || nodes.length === 0) {
    throw new Error('declare_commands packet has no command nodes.')
  }
  const rootIndex = packet.rootIndex ?? 0
  let current = requiredNode(nodes, rootIndex)
  for (const segment of path) {
    const childIndex = childIndexes(current)
      .find(index => {
        const child = requiredNode(nodes, index)
        return nodeType(child) === 1 && child.extraNodeData?.name === segment
      })
    if (childIndex === undefined) return undefined
    current = requiredNode(nodes, childIndex)
  }

  return childIndexes(current)
    .map(index => requiredNode(nodes, index))
    .filter(node => nodeType(node) === 1)
    .map(node => node.extraNodeData?.name)
    .filter(name => typeof name === 'string')
    .sort()
}

function nodeType (node) {
  if (Number.isInteger(node.flags?.command_node_type)) {
    return node.flags.command_node_type
  }
  return Number.isInteger(node.flags) ? node.flags & 0x03 : undefined
}

function requiredNode (nodes, index) {
  if (!Number.isInteger(index) || index < 0 || index >= nodes.length || nodes[index] == null) {
    throw new Error(`declare_commands packet references invalid node ${index}.`)
  }
  return nodes[index]
}

function childIndexes (node) {
  if (!Array.isArray(node.children)) {
    throw new Error('declare_commands node has no children array.')
  }
  return node.children
}

module.exports = { literalChildren, literalChildrenAt }