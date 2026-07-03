import QtQuick
import md3.Core
import "."

// Floating Dynamic Island pill pinned inside the status-bar area.
// Compact: tiny cover art + three animated waveform bars.
// Tap to expand: art, title, artist, progress, play/skip controls.
// Auto-collapses after 5 s of no interaction.
// Hidden while the lyric page is open (lyricSlide > 0).
Item {
    id: island
    anchors.fill: parent
    visible: player.title.length > 0 && player.lyricSlide < 0.001

    // Width of the navigation rail (or any left-side chrome). The pill is centered
    // over the content area, not the full window, so it stays clear of the rail
    // in wide/landscape/desktop layouts.
    property real contentLeft: 0

    property bool expanded: false

    Timer {
        id: collapseTimer
        interval: 5000
        onTriggered: island.expanded = false
    }

    // Full-screen backdrop — only active while expanded so it doesn't
    // swallow taps on the rest of the UI in compact mode.
    MouseArea {
        anchors.fill: parent
        enabled: island.expanded
        onClicked: island.expanded = false
    }

    // ── Pill ────────────────────────────────────────────────────────────
    Rectangle {
        id: pill

        anchors.horizontalCenter: parent.horizontalCenter
        // Shift the pill right so it centres over the content area rather than
        // the full window. On compact layouts contentLeft is 0 (no rail) so this
        // is a no-op; on wide/desktop the rail pushes it into the right region.
        anchors.horizontalCenterOffset: island.contentLeft / 2
        // Center vertically inside the status-bar inset; fall back to 8 dp
        // when there is no inset (e.g. tablet or desktop test).
        y: settings.topInset > 36 ? (settings.topInset - 36) / 2 : 8

        width:  island.expanded ? Math.min(parent.width - 32, 320) : 126
        height: island.expanded ? 88 : 36

        Behavior on width  { NumberAnimation { duration: 360; easing.type: Easing.OutCubic } }
        Behavior on height { NumberAnimation { duration: 360; easing.type: Easing.OutCubic } }

        color: Theme.color.inverseSurface
        radius: 22
        clip: true

        // ── Compact contents ─────────────────────────────────────────────
        // Small circular cover art on the left.
        CoverImage {
            id: compactCover
            anchors.left: parent.left
            anchors.leftMargin: 7
            anchors.verticalCenter: parent.verticalCenter
            width: 22; height: 22; radius: 11
            source: player.coverUrl
            opacity: island.expanded ? 0 : 1
            Behavior on opacity { NumberAnimation { duration: 150 } }
        }

        // Three waveform bars: animate height while playing, freeze while paused.
        // Each bar has a slightly longer period than the previous so they drift
        // out of phase naturally, giving an organic equaliser look.
        Item {
            id: waveArea
            anchors.centerIn: parent
            width: 15   // 3 bars × 3 px + 2 gaps × 3 px
            height: 20
            opacity: island.expanded ? 0 : 1
            Behavior on opacity { NumberAnimation { duration: 150 } }

            Repeater {
                model: 3
                Rectangle {
                    x: index * 6
                    // Keep the bar vertically centred as its height animates.
                    y: (waveArea.height - height) / 2
                    width: 3
                    height: 12
                    radius: 2
                    color: Theme.color.inverseOnSurface

                    SequentialAnimation on height {
                        loops: Animation.Infinite
                        running: player.playing && !island.expanded
                        NumberAnimation { to: 5;  duration: 280 + index * 70; easing.type: Easing.InOutSine }
                        NumberAnimation { to: 18; duration: 280 + index * 70; easing.type: Easing.InOutSine }
                    }
                }
            }
        }

        // Tap the compact pill to expand it.
        MouseArea {
            anchors.fill: parent
            enabled: !island.expanded
            onClicked: {
                island.expanded = true
                collapseTimer.restart()
            }
        }

        // ── Expanded contents ────────────────────────────────────────────
        // Large cover art on the left.
        CoverImage {
            id: bigCover
            anchors.left: parent.left
            anchors.leftMargin: 10
            anchors.verticalCenter: parent.verticalCenter
            width: 68; height: 68; radius: 10
            source: player.coverUrl
            opacity: island.expanded ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
        }

        // Play/pause + skip-next (bottom-right cluster).
        Row {
            id: ctrlRow
            anchors.right: parent.right
            anchors.rightMargin: 4
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 4
            spacing: 0
            opacity: island.expanded ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 220 } }

            IconButton {
                type: "standard"
                icon: player.playing ? "pause" : "play_arrow"
                contentColor: Theme.color.inverseOnSurface
                onClicked: { player.toggle(); collapseTimer.restart() }
            }
            IconButton {
                type: "standard"
                icon: "skip_next"
                contentColor: Theme.color.inverseOnSurface
                onClicked: { player.next(); collapseTimer.restart() }
            }
        }

        // Track title.
        Text {
            anchors.left: bigCover.right
            anchors.leftMargin: 10
            anchors.right: ctrlRow.left
            anchors.rightMargin: 4
            anchors.top: parent.top
            anchors.topMargin: 16
            text: player.title
            color: Theme.color.inverseOnSurface
            fontSize: 14
            elide: Text.ElideRight
            opacity: island.expanded ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 220 } }
        }

        // Artist name (slightly dimmed).
        Text {
            anchors.left: bigCover.right
            anchors.leftMargin: 10
            anchors.right: ctrlRow.left
            anchors.rightMargin: 4
            anchors.top: parent.top
            anchors.topMargin: 36
            text: player.artist
            color: Theme.color.inverseOnSurface
            fontSize: 12
            elide: Text.ElideRight
            opacity: island.expanded ? 0.6 : 0
            Behavior on opacity { NumberAnimation { duration: 220 } }
        }

        // Thin progress bar above the control buttons.
        Rectangle {
            anchors.left: bigCover.right
            anchors.leftMargin: 10
            anchors.right: ctrlRow.left
            anchors.rightMargin: 4
            anchors.bottom: ctrlRow.top
            anchors.bottomMargin: 8
            height: 3
            radius: 2
            color: "#40FFFFFF"
            opacity: island.expanded ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 220 } }

            Rectangle {
                anchors.left: parent.left
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: player.durationMs > 0
                    ? parent.width * Math.min(1, player.positionMs / player.durationMs) : 0
                radius: 2
                color: Theme.color.inverseOnSurface
            }
        }
    }
}
