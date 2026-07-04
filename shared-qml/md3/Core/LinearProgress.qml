import QtQuick
import md3.Core
Item {
    id: control
    
    property real value: 0.0
    property bool indeterminate: false
    property bool wavy: false

    // Override to use custom colours (e.g. white on the dark lyric backdrop).
    property color activeColor: _colors.primary
    property color trackColor: _colors.surfaceContainerHighest

    implicitWidth: 200
    implicitHeight: wavy ? 16 : 4

    property var _colors: Theme.color
    
    // Animation control
    property bool _initialized: false
    Component.onCompleted: _initialized = true
    
    property real _visualValue: Math.max(0.0, Math.min(1.0, control.value))
    Behavior on _visualValue {
        enabled: control._initialized
        NumberAnimation { duration: 200; easing.type: Easing.OutQuad }
    }
    
    // Standard Linear Progress
    Rectangle {
        id: track
        anchors.fill: parent
        visible: !control.wavy
        color: control.trackColor
        radius: height / 2
        clip: true
        
        // Determinate Indicator
        Rectangle {
            visible: !control.indeterminate
            height: parent.height
            width: parent.width * control._visualValue
            color: control.activeColor
            radius: height / 2
        }
        
        // Indeterminate Indicator
        Item {
            anchors.fill: parent
            visible: control.indeterminate
            
            // First bar
            Rectangle {
                id: bar1
                height: parent.height
                color: control.activeColor
                radius: height / 2
                
                SequentialAnimation {
                    running: control.indeterminate && control.visible && !control.wavy
                    loops: Animation.Infinite
                    
                    ParallelAnimation {
                        NumberAnimation { target: bar1; property: "x"; from: -parent.width; to: parent.width; duration: 2000; easing.type: Easing.InOutCubic }
                        SequentialAnimation {
                            NumberAnimation { target: bar1; property: "width"; from: 0; to: parent.width * 0.5; duration: 1000; easing.type: Easing.OutCubic }
                            NumberAnimation { target: bar1; property: "width"; from: parent.width * 0.5; to: 0; duration: 1000; easing.type: Easing.InCubic }
                        }
                    }
                }
            }
            
            // Second bar (delayed)
            Rectangle {
                id: bar2
                height: parent.height
                color: control.activeColor
                radius: height / 2
                
                SequentialAnimation {
                    running: control.indeterminate && control.visible && !control.wavy
                    loops: Animation.Infinite
                    
                    PauseAnimation { duration: 1000 }
                    
                    ParallelAnimation {
                        NumberAnimation { target: bar2; property: "x"; from: -parent.width; to: parent.width; duration: 2000; easing.type: Easing.InOutCubic }
                        SequentialAnimation {
                            NumberAnimation { target: bar2; property: "width"; from: 0; to: parent.width * 0.5; duration: 1000; easing.type: Easing.OutCubic }
                            NumberAnimation { target: bar2; property: "width"; from: parent.width * 0.5; to: 0; duration: 1000; easing.type: Easing.InCubic }
                        }
                    }
                }
            }
        }
    }

    // Wavy Linear Progress — pure QML dots on a sine path.
    // Canvas requestPaint() is not reliable in the qml4j GLFW backend, so we use a
    // Repeater of Rectangle dots. 80 dots spaced evenly across the width gives a
    // smooth-looking sine curve at typical mini-player widths.
    Item {
        id: wavyItem
        visible: control.wavy
        anchors.fill: parent
        clip: true

        property real phase: 0.0
        // Snapshot value so all 80 color bindings read the same value per frame.
        property real progress: Math.max(0.0, Math.min(1.0, control.value))

        Timer {
            running: control.wavy && control.visible
            interval: 33
            repeat: true
            onTriggered: wavyItem.phase = (wavyItem.phase + 0.25) % (Math.PI * 2)
        }

        Repeater {
            model: 80
            Rectangle {
                x: (index / 79.0) * wavyItem.width - 2
                y: wavyItem.height * 0.5
                  + Math.sin((index / 79.0) * wavyItem.width * 0.12 + wavyItem.phase)
                  * (wavyItem.height * 0.30) - 2
                width: 4
                height: 4
                radius: 2
                color: (index / 79.0) <= wavyItem.progress ? control.activeColor : control.trackColor
            }
        }
    }
}

