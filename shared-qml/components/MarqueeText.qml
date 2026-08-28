import QtQuick
import md3.Core

// A single-line Text that elides normally when it fits, and auto-scrolls
// back and forth with faded edges when it overflows -- for song/playlist
// titles too long to read via a static "..." truncation.
//
// The edge fade is NOT a layer.effect/MultiEffect mask: CoverImage.qml's own
// doc notes that path "allocates an offscreen surface per instance every
// frame" -- fine for a static rounded corner, but this component animates
// continuously while scrolling, which is exactly the expensive case that
// warns against. Instead each fade edge is a plain Rectangle with a
// Gradient(orientation: Horizontal) painted OVER the text, opaque fadeColor
// → transparent -- static, paid for once. (An earlier version stacked ~10
// solid Rectangles stepping opacity 1→0 as a poor-man's gradient, avoiding
// Gradient/GradientStop entirely since neither was used anywhere else in
// this codebase -- it rendered as a hard-edged solid bar, not a fade, so
// don't reintroduce that approach.)
//
// Plain Item + anchors-compatible sizing (no Layout wrapper), so it drops
// into both Layout-managed parents (PlaylistCard, page headers) and the
// anchors-only ones (MiniPlayer, LyricOverlay) alike.
Item {
    id: root

    property string text: ""
    property color textColor: "black"
    property real fontSize: 14
    property string fontFamily: ""
    property int fontWeight: Font.Normal
    property bool centered: false
    // Opaque colour the edges fade INTO -- must match whatever this sits on
    // (its container's own fill colour). There's no way to derive this
    // automatically since the component doesn't know its own backdrop.
    property color fadeColor: "black"
    property real fadeWidth: 20
    // Scroll pace (px/s) and the pause held at each end before reversing.
    property real speed: 32
    property int pauseMs: 1000

    implicitHeight: probe.implicitHeight
    clip: true

    // Hidden probe: measures the text's real unwrapped width, the same
    // "measure with an invisible Text" idiom AlbumCard's nameProbe uses.
    Text {
        id: probe
        visible: false
        text: root.text
        font.family: root.fontFamily
        font.pixelSize: root.fontSize
        font.weight: root.fontWeight
    }
    property bool overflowing: root.width > 0 && probe.implicitWidth > root.width

    onOverflowingChanged: if (!overflowing) label.scrollX = 0

    Text {
        id: label
        // A reactive default (not a fixed initial value): stays correctly at
        // 0 for text that never overflows at all, since onOverflowingChanged
        // below only fires on an actual TRUE→FALSE transition -- text that
        // was never overflowing in the first place would otherwise never get
        // reset off root.width (parked past the right edge) and just never
        // appear. Breaks (becomes a plain assigned value, no longer reactive)
        // once the scroll animation actually starts driving it, same as any
        // QML property binding does on its first imperative assignment.
        property real scrollX: root.overflowing ? root.width : 0
        // x is driven by the scroll animation; verticalCenter is independent
        // of that and keeps the text centered even when a caller's container
        // (anchors.fill: parent) is taller than one text line -- root's own
        // implicitHeight only matters when the caller sizes off THAT instead.
        x: scrollX
        anchors.verticalCenter: parent.verticalCenter
        width: root.overflowing ? probe.implicitWidth : root.width
        text: root.text
        color: root.textColor
        font.family: root.fontFamily
        font.pixelSize: root.fontSize
        font.weight: root.fontWeight
        elide: root.overflowing ? Text.ElideNone : Text.ElideRight
        horizontalAlignment: (root.centered && !root.overflowing) ? Text.AlignHCenter : Text.AlignLeft

        // One continuous pass, left-to-right reading order preserved (enters
        // from the right, exits on the left) -- not a back-and-forth bounce.
        // A blank pause (nothing on screen: the previous pass has fully
        // exited left, the next hasn't entered from the right yet) separates
        // each loop.
        SequentialAnimation {
            running: root.overflowing && root.visible
            loops: Animation.Infinite
            PauseAnimation { duration: root.pauseMs }
            NumberAnimation {
                target: label; property: "scrollX"
                from: root.width
                to: -probe.implicitWidth
                duration: (probe.implicitWidth + root.width) / root.speed * 1000
                easing.type: Easing.Linear
            }
        }
    }

    Rectangle {
        visible: root.overflowing
        anchors.left: parent.left
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: root.fadeWidth
        color: "transparent"
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0.0; color: root.fadeColor }
            GradientStop { position: 1.0; color: Qt.rgba(root.fadeColor.r, root.fadeColor.g, root.fadeColor.b, 0) }
        }
    }
    Rectangle {
        visible: root.overflowing
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        width: root.fadeWidth
        color: "transparent"
        gradient: Gradient {
            orientation: Gradient.Horizontal
            GradientStop { position: 0.0; color: Qt.rgba(root.fadeColor.r, root.fadeColor.g, root.fadeColor.b, 0) }
            GradientStop { position: 1.0; color: root.fadeColor }
        }
    }
}
