import QtQuick 2.15
import "components"

Item {
    id: root
    width: 900
    height: 180

    readonly property bool locked: desktopLyric.mousePassthrough

    // The render thread scales this complete subtree directly on the Skia canvas.
    // Keeping motion out of the QML transform list also avoids qml4j's global
    // picture-cache invalidation affecting the independently rendered main view.
    Item {
        id: chromeCanvas
        objectName: "desktopLyricChromeCanvas"
        anchors.fill: parent

        // Top-left: restore/open the player.
        DesktopLyricControlButton {
            x: 12
            y: 12
            icon: "open_in_new"
            onClicked: desktopLyric.openPlayer()
        }

        // Top-right: close desktop lyrics without closing the player.
        DesktopLyricControlButton {
            x: 850
            y: 12
            icon: "close"
            onClicked: desktopLyric.closeDesktopLyric()
        }

        // Bottom-left: playback controls stay grouped as one spatial unit.
        Item {
            x: 12
            y: 124
            width: 124
            height: 44

            DesktopLyricControlButton {
                anchors.left: parent.left
                icon: "skip_previous"
                onClicked: desktopLyric.previous()
            }

            DesktopLyricControlButton {
                anchors.horizontalCenter: parent.horizontalCenter
                icon: desktopLyric.playing ? "pause" : "play_arrow"
                emphasized: true
                onClicked: desktopLyric.togglePlayback()
            }

            DesktopLyricControlButton {
                anchors.right: parent.right
                icon: "skip_next"
                onClicked: desktopLyric.next()
            }
        }

        // Bottom-right: the unlocked control participates in the canvas motion.
        DesktopLyricControlButton {
            x: 850
            y: 124
            icon: "lock_open"
            visible: !root.locked
            onClicked: desktopLyric.toggleMousePassthrough()
        }
    }

    // Passthrough must remain reversible, so its locked-state control stays
    // outside the fading canvas at the same visual and native hit-test position.
    DesktopLyricControlButton {
        objectName: "desktopLyricLockedControl"
        x: 850
        y: 124
        icon: "lock"
        emphasized: true
        visible: root.locked
        onClicked: desktopLyric.toggleMousePassthrough()
    }
}
