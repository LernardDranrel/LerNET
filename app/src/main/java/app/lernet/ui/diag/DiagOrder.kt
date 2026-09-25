package app.lernet.ui.diag

import app.lernet.engine.live.LiveConn

internal fun newestPage(rows: List<LiveConn>, page: Int, pageSize: Int): List<LiveConn> =
    rows.asReversed().drop(page * pageSize).take(pageSize)
