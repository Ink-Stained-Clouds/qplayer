import QtQuick

// Shared state machine for root-page transitions. Keeping the animation objects
// out of Main.qml avoids qml4j's 64KB generated-constructor limit and gives both
// root navigation and ManagedPageLoader one source of motion parameters.
Item {
    id: motion

    // SettingsCatalog.PAGE_TRANSITION_*.
    property int preset: 0
    property int duration: preset === 4 ? 0 : 220
    // Slide presets also cross-fade. Pure translation left an abruptly opaque
    // page moving over its source and looked disconnected from the other presets.
    property real hiddenOpacity: 0
    property real hiddenScale: preset === 0 ? 0.94 : 1
    property real hiddenX: preset === 2 ? 44 : 0
    property real hiddenY: preset === 3 ? 40 : 0

    property real contentOpacity: 1
    property real contentScale: 1
    property real contentX: 0
    property real contentY: 0

    signal swapRequested()

    function prepareHidden() {
        motion.contentOpacity = motion.hiddenOpacity
        motion.contentScale = motion.hiddenScale
        motion.contentX = motion.hiddenX
        motion.contentY = motion.hiddenY
    }

    function stopAnimations() {
        transitionAnim.stop()
        entryAnim.stop()
        exitAnim.stop()
    }

    function showImmediately() {
        motion.stopAnimations()
        motion.contentOpacity = 1
        motion.contentScale = 1
        motion.contentX = 0
        motion.contentY = 0
    }

    function transition() {
        entryAnim.stop()
        exitAnim.stop()
        transitionAnim.restart()
    }

    function enter() {
        transitionAnim.stop()
        exitAnim.stop()
        motion.prepareHidden()
        entryAnim.restart()
    }

    function exit() {
        transitionAnim.stop()
        entryAnim.stop()
        exitAnim.restart()
    }

    SequentialAnimation {
        id: transitionAnim
        ParallelAnimation {
            NumberAnimation {
                target: motion; property: "contentOpacity"; to: motion.hiddenOpacity
                duration: motion.duration; easing.type: Easing.InCubic
            }
            NumberAnimation {
                target: motion; property: "contentScale"; to: motion.hiddenScale
                duration: motion.duration; easing.type: Easing.InCubic
            }
            NumberAnimation {
                target: motion; property: "contentX"; to: motion.hiddenX
                duration: motion.duration; easing.type: Easing.InCubic
            }
            NumberAnimation {
                target: motion; property: "contentY"; to: motion.hiddenY
                duration: motion.duration; easing.type: Easing.InCubic
            }
        }
        ScriptAction {
            onTrigger: {
                motion.swapRequested()
                motion.prepareHidden()
            }
        }
        ParallelAnimation {
            NumberAnimation {
                target: motion; property: "contentOpacity"; to: 1
                duration: motion.duration; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: motion; property: "contentScale"; to: 1
                duration: motion.duration; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: motion; property: "contentX"; to: 0
                duration: motion.duration; easing.type: Easing.OutCubic
            }
            NumberAnimation {
                target: motion; property: "contentY"; to: 0
                duration: motion.duration; easing.type: Easing.OutCubic
            }
        }
    }

    ParallelAnimation {
        id: entryAnim
        NumberAnimation {
            target: motion; property: "contentOpacity"; to: 1
            duration: motion.duration; easing.type: Easing.OutCubic
        }
        NumberAnimation {
            target: motion; property: "contentScale"; to: 1
            duration: motion.duration; easing.type: Easing.OutCubic
        }
        NumberAnimation {
            target: motion; property: "contentX"; to: 0
            duration: motion.duration; easing.type: Easing.OutCubic
        }
        NumberAnimation {
            target: motion; property: "contentY"; to: 0
            duration: motion.duration; easing.type: Easing.OutCubic
        }
    }

    ParallelAnimation {
        id: exitAnim
        NumberAnimation {
            target: motion; property: "contentOpacity"; to: motion.hiddenOpacity
            duration: motion.duration; easing.type: Easing.InCubic
        }
        NumberAnimation {
            target: motion; property: "contentScale"; to: motion.hiddenScale
            duration: motion.duration; easing.type: Easing.InCubic
        }
        NumberAnimation {
            target: motion; property: "contentX"; to: motion.hiddenX
            duration: motion.duration; easing.type: Easing.InCubic
        }
        NumberAnimation {
            target: motion; property: "contentY"; to: motion.hiddenY
            duration: motion.duration; easing.type: Easing.InCubic
        }
    }
}
