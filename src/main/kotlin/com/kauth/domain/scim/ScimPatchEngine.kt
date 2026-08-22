package com.kauth.domain.scim

// RFC 7644 §3.5.2 PATCH semantics implemented here:
//  - add on a multi-valued attribute appends to the existing collection.
//  - add/replace on a singular (sub-)attribute overwrites just that attribute.
//  - remove with a valued (filtered) path removes only the matching element(s);
//    a plain path clears the whole attribute.
//  - replace with a plain path on a multi-valued attribute replaces the entire list.
//  - a null path merges [ScimPatchOp.value] into the resource as a partial update.

enum class ScimPatchOpType { ADD, REPLACE, REMOVE }

/** A single PATCH operation. A null [path] means [value] is a partial resource to merge. */
data class ScimPatchOp(
    val op: ScimPatchOpType,
    val path: ScimPath?,
    val value: ScimValue?,
)

private val KNOWN_ATTRIBUTES =
    setOf("userName", "externalId", "displayName", "active", "name", "emails", "members", "id", "schemas", "meta")

class ScimPatchEngine {
    /** Applies [ops] in order to a copy of [resource]. All-or-nothing: [resource] is never mutated. */
    fun apply(
        resource: ScimResource,
        ops: List<ScimPatchOp>,
    ): Result<ScimResource> =
        try {
            var current = resource
            for (op in ops) {
                current = applyOp(current, op)
            }
            Result.success(current)
        } catch (e: PatchException) {
            Result.failure(ScimFailure(e.type, e.message ?: "invalid patch operation"))
        }

    private fun applyOp(
        resource: ScimResource,
        op: ScimPatchOp,
    ): ScimResource {
        val path = op.path ?: return mergePartial(resource, op)
        return when (path) {
            is ScimPath.Attr -> applyAttr(resource, op, path)
            is ScimPath.Valued -> applyValued(resource, op, path)
        }
    }

    private fun mergePartial(
        resource: ScimResource,
        op: ScimPatchOp,
    ): ScimResource {
        if (op.op == ScimPatchOpType.REMOVE) {
            throw PatchException(ScimErrorType.noTarget, "remove requires a path")
        }
        val partial =
            op.value as? ScimValue.Complex
                ?: throw PatchException(ScimErrorType.invalidValue, "a null path requires a complex value")
        partial.attributes.keys.forEach(::requireKnownAttribute)
        return resource.copy(attributes = resource.attributes + partial.attributes)
    }

    private fun applyAttr(
        resource: ScimResource,
        op: ScimPatchOp,
        path: ScimPath.Attr,
    ): ScimResource {
        requireKnownAttribute(path.name)
        return when (op.op) {
            ScimPatchOpType.REMOVE -> removeAttr(resource, path)
            ScimPatchOpType.ADD -> addAttr(resource, path, op.value)
            ScimPatchOpType.REPLACE -> replaceAttr(resource, path, op.value)
        }
    }

    private fun removeAttr(
        resource: ScimResource,
        path: ScimPath.Attr,
    ): ScimResource {
        val sub = path.sub ?: return resource.copy(attributes = resource.attributes - path.name)
        val current = resource.attributes[path.name] as? ScimValue.Complex ?: return resource
        if (sub !in current.attributes) return resource
        val updated = current.copy(attributes = current.attributes - sub)
        return resource.copy(attributes = resource.attributes + (path.name to updated))
    }

    private fun addAttr(
        resource: ScimResource,
        path: ScimPath.Attr,
        value: ScimValue?,
    ): ScimResource {
        val newValue = value ?: throw PatchException(ScimErrorType.invalidValue, "add requires a value")
        // Sub-attributes are singular, so adding one is the same as replacing it.
        if (path.sub != null) {
            return replaceAttr(resource, path, newValue)
        }
        if (newValue is ScimValue.MultiValued) {
            val existing = (resource.attributes[path.name] as? ScimValue.MultiValued)?.values ?: emptyList()
            val appended = ScimValue.MultiValued(existing + newValue.values)
            return resource.copy(attributes = resource.attributes + (path.name to appended))
        }
        // Adding a singular value to a singular attribute is equivalent to replacing it.
        return resource.copy(attributes = resource.attributes + (path.name to newValue))
    }

    private fun replaceAttr(
        resource: ScimResource,
        path: ScimPath.Attr,
        value: ScimValue?,
    ): ScimResource {
        val newValue = value ?: throw PatchException(ScimErrorType.invalidValue, "replace requires a value")
        val sub = path.sub ?: return resource.copy(attributes = resource.attributes + (path.name to newValue))
        val complex =
            when (val current = resource.attributes[path.name]) {
                null -> ScimValue.Complex(emptyMap())
                is ScimValue.Complex -> current
                else -> throw PatchException(ScimErrorType.invalidValue, "'${path.name}' is not a complex attribute")
            }
        val updated = complex.copy(attributes = complex.attributes + (sub to newValue))
        return resource.copy(attributes = resource.attributes + (path.name to updated))
    }

    private fun applyValued(
        resource: ScimResource,
        op: ScimPatchOp,
        path: ScimPath.Valued,
    ): ScimResource {
        val attr = path.attr
        requireKnownAttribute(attr.name)
        // A valued path targets elements of an existing collection; nothing to target
        // when the attribute is absent means the operation is a no-op, not an error.
        val existing = resource.attributes[attr.name] ?: return resource
        val current =
            existing as? ScimValue.MultiValued
                ?: throw PatchException(ScimErrorType.invalidValue, "'${attr.name}' is not multi-valued")
        return when (op.op) {
            ScimPatchOpType.REMOVE -> removeValued(resource, attr, path, current)
            ScimPatchOpType.ADD, ScimPatchOpType.REPLACE -> replaceValued(resource, attr, path, current, op)
        }
    }

    private fun removeValued(
        resource: ScimResource,
        attr: ScimPath.Attr,
        path: ScimPath.Valued,
        current: ScimValue.MultiValued,
    ): ScimResource {
        val sub = path.sub
        val updated =
            if (sub == null) {
                current.values.filterNot { path.filter.matches(it) }
            } else {
                current.values.map { element ->
                    if (element is ScimValue.Complex && path.filter.matches(element)) {
                        element.copy(attributes = element.attributes - sub)
                    } else {
                        element
                    }
                }
            }
        return resource.copy(attributes = resource.attributes + (attr.name to ScimValue.MultiValued(updated)))
    }

    private fun replaceValued(
        resource: ScimResource,
        attr: ScimPath.Attr,
        path: ScimPath.Valued,
        current: ScimValue.MultiValued,
        op: ScimPatchOp,
    ): ScimResource {
        val newValue = op.value ?: throw PatchException(ScimErrorType.invalidValue, "${op.op} requires a value")
        val sub = path.sub
        val updated =
            current.values.map { element ->
                if (!path.filter.matches(element)) {
                    element
                } else if (sub != null) {
                    val complex =
                        element as? ScimValue.Complex
                            ?: throw PatchException(ScimErrorType.invalidValue, "matched element is not complex")
                    complex.copy(attributes = complex.attributes + (sub to newValue))
                } else {
                    newValue
                }
            }
        return resource.copy(attributes = resource.attributes + (attr.name to ScimValue.MultiValued(updated)))
    }

    private fun requireKnownAttribute(name: String) {
        if (name !in KNOWN_ATTRIBUTES) {
            throw PatchException(ScimErrorType.invalidPath, "unknown attribute '$name'")
        }
    }
}

/** Internal-only: carries a typed, human-readable apply failure up to [ScimPatchEngine.apply]. */
private class PatchException(
    val type: ScimErrorType,
    message: String,
) : Exception(message)
