package com.cleansweep.app.data.local.db

/**
 * Android 10 ships SQLite 3.22, which caps a statement at 999 host parameters (the limit is far higher from
 * Android 12 on). Collections bound through `IN (...)` are chunked below this; 900 leaves headroom for the
 * statement's other arguments. Room's list upserts bind one row at a time and are not affected.
 *
 * The same floor explains why no query here uses UPSERT syntax, RETURNING or window functions: all of them need a
 * newer SQLite than Android 10 provides.
 */
const val MAX_BIND_ARGS_PER_QUERY: Int = 900
