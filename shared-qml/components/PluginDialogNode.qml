// One node of a plugin-described dialog, drawn with QPlayer's own components.
// PluginDialog instantiates a fixed number of these and binds each to one entry
// of the description's body. They are declared statically rather than through a
// Repeater on purpose: qml4j does not position Repeater-created children the way
// a Qt positioner would.

// The node object has already been validated host-side (PluginUiDescription), so
// this file only has to render known shapes -- it never has to defend itself
// against arbitrary plugin data.
import QtQuick
import md3.Core

Item {
    id: slot

    property var node: null
    property bool busy: false
    // Current text of this slot, when it is an input.
    property string inputValue: ""
    // Live state of this slot, when it is a switch. Read straight off the
    // control so an untouched switch still reports what it is showing.
    readonly property bool switchValue: nodeType === "switch" && toggle.checked

    // The dialog this slot belongs to. Buttons call host.submit(id) directly:
    // no signal in shared-qml carries parameters, and qml4j has no precedent for
    // a parameterized handler, so a plain callback property is the safe shape.
    property var host: null

    readonly property string nodeType: node ? (node.type || "") : ""
    readonly property string nodeStyle: node && node.style ? node.style : "body"
    // Buttons are laid out by hand: deeply nested Layout fillWidth does not
    // propagate reliably in qml4j.
    readonly property int rowCount: nodeType === "row" && node.items ? node.items.length : 0
    readonly property real rowSpacing: 12
    readonly property real rowButtonWidth: {
        var hostWidth = width > 0 ? width : implicitWidth
        return rowCount > 0 ? (hostWidth - rowSpacing * (rowCount - 1)) / rowCount : 0
    }

    // Seed the field from the description only when this slot starts showing a
    // different input. A periodic refresh must never overwrite what the user is
    // typing.
    property string appliedInputId: ""

    implicitWidth: 280
    implicitHeight: {
        if (!visible)
            return 0
        if (nodeType === "spacer")
            return node && node.height !== undefined ? node.height : 8
        if (nodeType === "input")
            return field.implicitHeight
        if (nodeType === "switch")
            return 40 + (switchDesc.visible ? switchDesc.implicitHeight + 4 : 0)
        if (nodeType === "button" || nodeType === "row")
            return 44
        if (nodeType === "text" || nodeType === "error")
            return label.implicitHeight
        return 0
    }
    visible: node !== null
    onNodeChanged: {
        if (slot.nodeType === "input" && slot.node.id !== slot.appliedInputId) {
            slot.appliedInputId = slot.node.id;
            field.text = slot.node.value || "";
            slot.inputValue = field.text;
        }

    }

    Text {
        id: label

        anchors.left: parent.left
        anchors.right: parent.right
        visible: slot.nodeType === "text" || slot.nodeType === "error"
        text: slot.node && slot.node.text ? slot.node.text : ""
        color: slot.nodeType === "error" ? Theme.color.error
               : slot.nodeStyle === "caption" ? Theme.color.onSurfaceVariantColor
               : Theme.color.onSurfaceColor
        fontSize: slot.nodeStyle === "title" ? 17 : slot.nodeStyle === "caption" ? 12 : 14
        font.bold: slot.nodeStyle === "title"
        horizontalAlignment: (slot.nodeType === "error"
                              || (slot.node && slot.node.center === true))
                             ? Text.AlignHCenter : Text.AlignLeft
        wrapMode: Text.Wrap
    }

    TextField {
        id: field

        anchors.left: parent.left
        anchors.right: parent.right
        height: visible ? implicitHeight : 0
        visible: slot.nodeType === "input"
        type: "outlined"
        enabled: !slot.busy
        label: slot.node && slot.node.placeholder ? slot.node.placeholder : ""
        isPassword: slot.node ? slot.node.secret === true : false
        onTextChanged: slot.inputValue = text
    }

    // A settings-style toggle, laid out like the host's own switch rows so a
    // plugin's on/off state never has to be drawn as a pair of buttons.
    Item {
        id: switchRow

        anchors.left: parent.left
        anchors.right: parent.right
        height: 40
        visible: slot.nodeType === "switch"

        Text {
            anchors.left: parent.left
            anchors.right: toggle.left
            anchors.rightMargin: 12
            anchors.verticalCenter: parent.verticalCenter
            text: slot.node && slot.node.label ? slot.node.label : ""
            color: Theme.color.onSurfaceColor
            font.family: Theme.typography.bodyLarge.family
            font.pixelSize: Theme.typography.bodyLarge.size
            wrapMode: Text.Wrap
        }

        Switch {
            id: toggle

            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            // The plugin owns this state: every description it returns, including
            // the one answering a toggle, re-seeds the control.
            checked: slot.node && slot.node.checked === true
            enabled: slot.node ? slot.node.enabled !== false && !slot.busy : false
            onClicked: {
                if (slot.host)
                    slot.host.submit(slot.node.id);
            }
        }
    }

    Text {
        id: switchDesc

        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: switchRow.bottom
        anchors.topMargin: 4
        visible: slot.nodeType === "switch" && text.length > 0
        text: slot.node && slot.node.desc ? slot.node.desc : ""
        color: Theme.color.onSurfaceVariantColor
        font.family: Theme.typography.bodySmall.family
        font.pixelSize: Theme.typography.bodySmall.size
        wrapMode: Text.Wrap
    }

    Button {
        id: actionButton

        anchors.left: parent.left
        anchors.right: parent.right
        height: 44
        visible: slot.nodeType === "button"
        text: slot.node ? slot.node.label : ""
        type: slot.node ? slot.node.style : "filled"
        enabled: slot.node && slot.node.enabled === true && !slot.busy
        onClicked: {
            if (slot.host)
                slot.host.submit(slot.node.id)
        }
    }

    Item {
        id: buttonRow

        anchors.left: parent.left
        anchors.right: parent.right
        height: 44
        visible: slot.nodeType === "row"

        Button {
            width: slot.rowButtonWidth
            height: parent.height
            visible: slot.rowCount > 0
            x: 0
            text: slot.rowCount > 0 ? slot.node.items[0].label : ""
            type: slot.rowCount > 0 ? slot.node.items[0].style : "filled"
            enabled: slot.rowCount > 0 && slot.node.items[0].enabled && !slot.busy
            onClicked: {
                if (slot.host)
                    slot.host.submit(slot.node.items[0].id)
            }
        }

        Button {
            width: slot.rowButtonWidth
            height: parent.height
            visible: slot.rowCount > 1
            x: slot.rowButtonWidth + slot.rowSpacing
            text: slot.rowCount > 1 ? slot.node.items[1].label : ""
            type: slot.rowCount > 1 ? slot.node.items[1].style : "filled"
            enabled: slot.rowCount > 1 && slot.node.items[1].enabled && !slot.busy
            onClicked: {
                if (slot.host)
                    slot.host.submit(slot.node.items[1].id)
            }
        }

        Button {
            width: slot.rowButtonWidth
            height: parent.height
            visible: slot.rowCount > 2
            x: 2 * (slot.rowButtonWidth + slot.rowSpacing)
            text: slot.rowCount > 2 ? slot.node.items[2].label : ""
            type: slot.rowCount > 2 ? slot.node.items[2].style : "filled"
            enabled: slot.rowCount > 2 && slot.node.items[2].enabled && !slot.busy
            onClicked: {
                if (slot.host)
                    slot.host.submit(slot.node.items[2].id)
            }
        }
    }
}
