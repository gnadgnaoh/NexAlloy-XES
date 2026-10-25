package io.github.nexalloy.morphe.youtube.misc.navigation

import io.github.nexalloy.RequireAppVersion
import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.ResourceType
import io.github.nexalloy.morphe.accessFlags
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.resourceLiteral
import io.github.nexalloy.morphe.resourceMappings
import io.github.nexalloy.morphe.returns
import io.github.nexalloy.morphe.string

val toolbarContainerId get() = resourceMappings["id", "toolbar_container"]

object ToolbarLayoutFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "toolbar_container")
    )
)

/**
 * Matches to https://android.googlesource.com/platform/frameworks/support/+/9eee6ba/v7/appcompat/src/android/support/v7/widget/Toolbar.java#963
 */
object AppCompatToolbarBackButtonFingerprint : Fingerprint(
    definingClass = "Landroid/support/v7/widget/Toolbar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/graphics/drawable/Drawable;",
    parameters = listOf()
)

/**
 * Matches to the class found in [PivotBarConstructorFingerprint].
 */
internal object InitializeButtonsFingerprint : Fingerprint(
    classFingerprint = PivotBarConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        string("FEvideo_picker")
    )
)

val getNavigationEnumMethod = findMethodDirect {
    InitializeButtonsFingerprint().invokes.findMethod {
        matcher {
            declaredClass(navigationEnumClass(this@findMethodDirect).name)
            accessFlags(AccessFlags.STATIC)
        }
    }.single()
}

/**
 * Matches to the Enum class that looks up ordinal -> instance.
 */
internal object NavigationEnumFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    strings = listOf(
        "PIVOT_HOME",
        "TAB_SHORTS",
        "CREATION_TAB_LARGE",
        "PIVOT_SUBSCRIPTIONS",
        "TAB_ACTIVITY",
        "VIDEO_LIBRARY_WHITE",
        "INCOGNITO_CIRCLE",
    )
)

val navigationEnumClass = findClassDirect { NavigationEnumFingerprint().declaredClass!! }

/**
 * 21.42 and earlier.
 */
@get:RequireAppVersion(maxVersion = "21.43.000")
val pivotBarButtonsCreateDrawableViewFingerprint = findMethodDirect {
    findMethod {
        matcher {
            accessFlags(AccessFlags.PUBLIC, AccessFlags.FINAL)
            returns("Landroid/view/View;")
            declaredClass {
                descriptor =
                    "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;"
            }
        }
    }.single {
        it.paramTypes.firstOrNull()?.descriptor == "Landroid/graphics/drawable/Drawable;"
    }
}

object PivotBarButtonsCreateResourceStyledViewFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf("L", "Z", "I", "L")
)

internal object PivotBarButtonsViewSetSelectedFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "Z"),
    filters = listOf(
        methodCall(name = "setSelected")
    )
)

val pivotBarButtonsViewSetSelectedSubFingerprint = findMethodDirect {
    PivotBarButtonsViewSetSelectedFingerprint.instructionMatches.single().instruction.methodRef!!
}

internal object PivotBarDispatchTouchEventFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;",
    name = "dispatchTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;")
)

internal object PivotBarConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        string("com.google.android.apps.youtube.app.endpoint.flags"),
    )
)

internal object InitializeBottomBarContainerFingerprint : Fingerprint(
    name = "run",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "bottom_bar_container"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = $$"Landroid/view/View;->addOnLayoutChangeListener(Landroid/view/View$OnLayoutChangeListener;)V"
        )
    )
)

internal val bottomBarContainerListenerMethod = findMethodDirect {
    InitializeBottomBarContainerFingerprint.instructionMatches.last().instruction.methodRef!!
}

val getNavIconResIdFingerprint = findMethodListDirect {
    // two matches in versions 20.24.xx-20.26.xx,
    // one match in versions <=v20.20.xx and >=v20.28.xx
    val navigationEnumClass = navigationEnumClass()
    findMethod {
        matcher {
            paramTypes(navigationEnumClass.name, "boolean")
            returnType = "int"
        }
    }
}
