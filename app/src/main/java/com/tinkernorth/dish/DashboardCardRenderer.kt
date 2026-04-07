package com.tinkernorth.dish

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Builds and updates the controller dashboard cards.
 *
 * Pure view construction — reads [ControllerEntry] state to produce Views.
 * All user actions are forwarded via callbacks:
 *   [onScanRequested], [onDisconnectNow], [onServerSelected], [onControllerTypeChanged].
 */
@Suppress("TooManyFunctions")
class DashboardCardRenderer(
    private val context: Context,
) {
    // ── Action callbacks ──────────────────────────────────────────────────
    var onScanRequested: (() -> Unit)? = null
    var onDisconnectNow: ((ControllerEntry) -> Unit)? = null
    var onServerSelected: ((DiscoveredServer) -> Unit)? = null
    var onControllerTypeChanged: ((ControllerEntry, ControllerType) -> Unit)? = null
    var onRescanRequested: (() -> Unit)? = null

    // ── Layout references ─────────────────────────────────────────────────
    data class Views(
        val llEmptyState: LinearLayout,
        val llControllerCards: LinearLayout,
        val dotServer: View,
        val tvServerStatus: TextView,
        val btnDisconnectAll: MaterialButton,
    )

    var views: Views? = null

    // ── Public API ────────────────────────────────────────────────────────

    fun refresh(
        entries: Collection<ControllerEntry>,
        serverConnected: Boolean,
        serverName: String?,
    ) {
        val v = views ?: return
        val container = v.llControllerCards
        container.removeAllViews()

        if (entries.isEmpty()) {
            v.llEmptyState.visibility = View.VISIBLE
            container.visibility = View.GONE
        } else {
            v.llEmptyState.visibility = View.GONE
            container.visibility = View.VISIBLE
            entries.forEach { entry ->
                val card = buildCard(entry)
                entry.cardView = card
                container.addView(card)
            }
        }
        updateServerStatus(serverConnected, serverName)
    }

    /** Rebuild a single card's content without rebuilding all cards. */
    fun rebuildCardContent(entry: ControllerEntry) {
        val content = entry.contentContainer ?: return
        content.removeAllViews()
        populateCardContent(content, entry)
    }

    /** Show discovered servers inside the first SERVER_LIST card. */
    fun populateServerList(entry: ControllerEntry, servers: List<DiscoveredServer>) {
        val content = entry.contentContainer ?: return
        val dp = context.resources.displayMetrics.density
        servers.forEach { server ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.colorBackground))
                    cornerRadius = 4 * dp
                    setStroke((1 * dp).toInt(), context.getColor(R.color.colorOutline))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * dp).toInt() }
                setOnClickListener { onServerSelected?.invoke(server) }
            }
            item.addView(TextView(context).apply {
                text = server.name
                setTextColor(context.getColor(R.color.colorOnSurface))
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            })
            item.addView(TextView(context).apply {
                text = "${server.ip}  •  UDP:${server.udpPort}"
                setTextColor(context.getColor(R.color.colorMuted))
                textSize = 12f; typeface = Typeface.MONOSPACE
            })
            content.addView(item)
        }
        content.addView(MaterialButton(context).apply {
            text = "Rescan"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * dp).toInt() }
            setOnClickListener { onRescanRequested?.invoke() }
        })
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun updateServerStatus(connected: Boolean, name: String?) {
        val v = views ?: return
        val color = if (connected) R.color.colorSuccess else R.color.colorMuted
        (v.dotServer.background as? GradientDrawable)?.setColor(context.getColor(color))
        v.tvServerStatus.text = if (connected) name ?: "CONNECTED" else "NOT CONNECTED"
        v.tvServerStatus.setTextColor(context.getColor(color))
        v.btnDisconnectAll.visibility = if (connected) View.VISIBLE else View.GONE
    }

    private fun buildCard(entry: ControllerEntry): MaterialCardView {
        val dp = context.resources.displayMetrics.density
        val card = MaterialCardView(context).apply {
            setCardBackgroundColor(context.getColor(R.color.colorSurface))
            strokeColor = context.getColor(R.color.colorOutline)
            strokeWidth = (1 * dp).toInt()
            radius = 8 * dp
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (12 * dp).toInt() }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt(); setPadding(pad, pad, pad, pad)
        }
        entry.contentContainer = content
        card.addView(content)
        populateCardContent(content, entry)
        return card
    }

    @Suppress("LongMethod")
    private fun populateCardContent(container: LinearLayout, entry: ControllerEntry) {
        val dp = context.resources.displayMetrics.density
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(View(context).apply {
            val size = (10 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (8 * dp).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(stateColor(entry.cardState)))
            }
        })
        header.addView(TextView(context).apply {
            text = entry.name; setTextColor(context.getColor(R.color.colorOnSurface))
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            text = "#${entry.controllerIndex}"
            setTextColor(context.getColor(R.color.colorMuted)); textSize = 12f; typeface = Typeface.MONOSPACE
        })
        container.addView(header)

        when (entry.cardState) {
            ControllerCardState.NEED_SERVER -> {
                addLabel(container, "No servers found", R.color.colorMuted)
                addControllerTypeSelector(container, entry, dp)
                container.addView(MaterialButton(context).apply {
                    text = "Scan for Servers"
                    setOnClickListener { onScanRequested?.invoke() }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (8 * dp).toInt() }
                })
            }
            ControllerCardState.SCANNING -> {
                addLabel(container, "Scanning for servers…", R.color.colorMuted)
                container.addView(ProgressBar(context).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (8 * dp).toInt(); gravity = Gravity.CENTER_HORIZONTAL }
                })
            }
            ControllerCardState.SERVER_LIST -> addLabel(container, "Select a server:", R.color.colorMuted)
            ControllerCardState.ADDING -> {
                addLabel(container, "Adding to server…", R.color.colorMuted)
                container.addView(ProgressBar(context).apply {
                    isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (8 * dp).toInt(); gravity = Gravity.CENTER_HORIZONTAL }
                })
            }
            ControllerCardState.ACTIVE -> {
                val statusText = if (entry.vigemActive) "Streaming" else "Connected (no ViGEm)"
                val statusColor = if (entry.vigemActive) R.color.colorSuccess else R.color.colorWarning
                addLabel(container, statusText, statusColor)
                addControllerTypeSelector(container, entry, dp)
            }
            ControllerCardState.DISCONNECTING -> {
                addLabel(container, "Controller disconnected — removing in ${entry.countdownSeconds}s", R.color.colorWarning)
                container.addView(MaterialButton(context).apply {
                    text = "Disconnect Now"
                    setOnClickListener { onDisconnectNow?.invoke(entry) }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = (8 * dp).toInt() }
                })
            }
        }
    }

    private fun addLabel(parent: LinearLayout, text: String, colorRes: Int) {
        val dp = context.resources.displayMetrics.density
        parent.addView(TextView(context).apply {
            this.text = text; setTextColor(context.getColor(colorRes)); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (4 * dp).toInt() }
        })
    }

    private fun addControllerTypeSelector(parent: LinearLayout, entry: ControllerEntry, dp: Float) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * dp).toInt() }
        }
        val icon = ImageView(context).apply {
            setImageResource(entry.controllerType.drawableRes)
            layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply {
                marginEnd = (8 * dp).toInt()
            }
        }
        row.addView(icon)
        row.addView(TextView(context).apply {
            text = entry.controllerType.label
            setTextColor(context.getColor(R.color.colorOnSurface)); textSize = 14f
        })
        row.setOnClickListener { showControllerTypePicker(entry) }
        parent.addView(row)
    }

    private fun showControllerTypePicker(entry: ControllerEntry) {
        val current = ControllerType.entries.indexOf(entry.controllerType)
        MaterialAlertDialogBuilder(context)
            .setTitle("Controller Type")
            .setSingleChoiceItems(ControllerType.labels.toTypedArray(), current) { dialog, which ->
                val newType = ControllerType.fromIndex(which)
                onControllerTypeChanged?.invoke(entry, newType)
                dialog.dismiss()
            }
            .show()
    }

    companion object {
        fun stateColor(state: ControllerCardState) = when (state) {
            ControllerCardState.ACTIVE -> R.color.colorSuccess
            ControllerCardState.DISCONNECTING -> R.color.colorWarning
            ControllerCardState.SCANNING, ControllerCardState.ADDING -> R.color.colorPrimary
            else -> R.color.colorMuted
        }
    }
}
