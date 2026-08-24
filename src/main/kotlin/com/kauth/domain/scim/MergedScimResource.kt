package com.kauth.domain.scim

/**
 * Marks a [ScimResource] as the complete, fully merged desired state for a resource — the only
 * shape `toDomain` on a SCIM mapper may accept. An attribute absent from a [ScimResource] means
 * "clear this field"; that reading is correct for PUT, where the request body already is the
 * desired end state, and for a PATCH once its operations have been merged onto
 * `toResource(existing)` (see `ScimPatchEngine`). It is wrong — and silently destructive — for a
 * raw PATCH body, where an unmentioned attribute means "leave alone", not "clear". Wrapping the
 * resource in this type at the call site is what makes that merge step a compile-time
 * requirement instead of a convention documented only in a KDoc comment.
 */
@JvmInline
value class MergedScimResource(
    val value: ScimResource,
)
