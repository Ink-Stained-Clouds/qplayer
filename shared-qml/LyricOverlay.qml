import QtQuick
import md3.Core
import "."

// QML chrome for the lyric page, composited on top of the host-drawn fluid
// backdrop + per-syllable lyrics. Transparent everywhere except the title band
// (top) and the transport band (bottom), so the host lyrics show through the
// middle. Visibility/opacity follow player.lyricSlide (published by the host) so
// it fades in lockstep with the host layer.
Item {
    id: overlay

    function fmt(ms) {
        if (ms <= 0) return "0:00";
        var s = Math.floor(ms / 1000), m = Math.floor(s / 60), r = s % 60;
        return m + ":" + (r < 10 ? "0" + r : r);
    }

    // Swallow taps on the empty (lyrics) area so they don't leak through.
    // The lyricDragArea declared below takes priority in the middle region
    // because it is rendered on top (declared later in source order).
    MouseArea { anchors.fill: parent }

    // --- top: dismiss + title + artist ---------------------------------
    IconButton {
        id: backBtn
        anchors.top: parent.top
        anchors.topMargin: settings.topInset + 6
        anchors.left: parent.left
        anchors.leftMargin: 6
        type: "standard"
        icon: "expand_more"
        contentColor: "#FFFFFFFF"
        onClicked: player.setLyricsOpen(false)
    }

    Text {
        id: titleText
        anchors.top: backBtn.bottom
        anchors.topMargin: 2
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        text: player.title
        color: "#FFFFFFFF"
        font.family: Theme.typography.titleLarge.family
        font.pixelSize: 22
        wrapMode: Text.WordWrap
        maximumLineCount: 2
        elide: Text.ElideRight
    }
    Text {
        id: artistText
        anchors.top: titleText.bottom
        anchors.topMargin: 4
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        text: player.artist
        color: "#B3FFFFFF"
        fontSize: 14
        elide: Text.ElideRight
    }

    // --- middle: album cover (pure-music / no lyrics) -----------------
    Item {
        visible: !player.hasLyrics
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: artistText.bottom
        anchors.bottom: transport.top
        anchors.topMargin: 16
        anchors.bottomMargin: 16

        CoverImage {
            anchors.centerIn: parent
            width: Math.max(80, Math.min(parent.width - 56, parent.height - 56))
            height: width
            radius: 12
            source: player.coverPath !== "" ? player.coverPath : player.coverUrl
        }
    }

    // --- bottom: transport --------------------------------------------
    Item {
        id: transport
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.bottomMargin: settings.bottomInset + 12
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        height: 120

        // progress (md3 wavy) + seek. The wavy phase is an infinite animation gated
        // on the bar's OWN `visible` (control.visible) — own visibility is not the
        // ancestor-effective one, so when the lyric page is closed (this whole
        // overlay invisible) the bar's own visible stayed true and the animation
        // kept ticking every frame, bumping the change version and defeating the
        // renderer's idle layout-skip. Tie its visibility to the page being shown.
        LinearProgress {
            id: progress
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.topMargin: 18
            wavy: settings.lyricWavy
            value: player.lyricProgress
            activeColor: "#FFFFFFFF"
            trackColor: "#33FFFFFF"
        }
        MouseArea {
            anchors.fill: progress
            anchors.topMargin: -10
            anchors.bottomMargin: -10
            onPressed: if (player.durationMs > 0)
                           player.seek(Math.round(mouseX / width * player.durationMs))
            onPositionChanged: if (pressed && player.durationMs > 0)
                                   player.seek(Math.round(Math.max(0, Math.min(width, mouseX)) / width * player.durationMs))
        }
        Text {
            anchors.left: parent.left
            anchors.top: progress.bottom
            anchors.topMargin: 6
            text: overlay.fmt(player.positionMs)
            color: "#B3FFFFFF"
            fontSize: 11
        }
        Text {
            anchors.right: parent.right
            anchors.top: progress.bottom
            anchors.topMargin: 6
            text: overlay.fmt(player.durationMs)
            color: "#B3FFFFFF"
            fontSize: 11
        }

        // transport buttons
        Row {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 14
            spacing: 18
            IconButton {
                type: "standard"
                icon: player.playMode === 1 ? "shuffle"
                      : (player.playMode === 2 ? "repeat_one" : "repeat")
                contentColor: player.playMode === 0 ? "#99FFFFFF" : "#FF82B1FF"
                onClicked: player.cyclePlayMode()
            }
            IconButton {
                type: "standard"; icon: "skip_previous"
                contentColor: "#FFFFFFFF"
                onClicked: player.prev()
            }
            IconButton {
                type: "filled"
                icon: player.playing ? "pause" : "play_arrow"
                onClicked: player.toggle()
            }
            IconButton {
                type: "standard"; icon: "skip_next"
                contentColor: "#FFFFFFFF"
                onClicked: player.next()
            }
            IconButton {
                type: "standard"
                enabled: player.currentLikeable
                icon: player.currentLiked ? "favorite" : "favorite_border"
                contentColor: player.currentLiked ? "#FFFF5277" : "#99FFFFFF"
                onClicked: player.toggleLike()
            }
        }
    }

    // --- lyric area: vertical drag to seek ----------------------------
    // Spans the transparent middle between the title block and the transport.
    // Declared after the full-screen swallow MouseArea so it has higher
    // priority and intercepts touch in the lyrics region.
    Item {
        id: lyricDragArea
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: artistText.bottom
        anchors.topMargin: 8
        anchors.bottom: transport.top

        property bool dragging: false
        property real dragY: 0
        property real dragProgress: 0.5  // 0..1 within this area → maps to 0..durationMs

        MouseArea {
            anchors.fill: parent
            onPressed: {
                lyricDragArea.dragging = true
                lyricDragArea.dragY = mouseY
                lyricDragArea.dragProgress = Math.max(0, Math.min(1, mouseY / height))
            }
            onPositionChanged: {
                lyricDragArea.dragY = mouseY
                lyricDragArea.dragProgress = Math.max(0, Math.min(1, mouseY / height))
            }
            onReleased: {
                if (player.durationMs > 0)
                    player.seek(Math.round(lyricDragArea.dragProgress * player.durationMs))
                lyricDragArea.dragging = false
            }
            onCanceled: { lyricDragArea.dragging = false }
        }

        // Horizontal seek line
        Rectangle {
            anchors.left: parent.left
            anchors.right: parent.right
            y: lyricDragArea.dragY
            height: 1
            color: "#FFFFFFFF"
            opacity: 0.7
            visible: lyricDragArea.dragging
        }

        // Small dot anchoring the left end of the seek line
        Rectangle {
            x: 20
            y: lyricDragArea.dragY - 5
            width: 10; height: 10; radius: 5
            color: "#FFFFFFFF"
            visible: lyricDragArea.dragging
        }

        // Time badge on the right — clipped to stay within the drag area
        Rectangle {
            anchors.right: parent.right
            anchors.rightMargin: 20
            y: Math.max(0, Math.min(parent.height - 32, lyricDragArea.dragY - 16))
            width: seekTimeText.width + 20
            height: 32
            radius: 8
            color: "#CC000000"
            visible: lyricDragArea.dragging

            Text {
                id: seekTimeText
                anchors.centerIn: parent
                text: overlay.fmt(Math.round(lyricDragArea.dragProgress * player.durationMs))
                color: "#FFFFFFFF"
                fontSize: 13
            }
        }
    }
}
