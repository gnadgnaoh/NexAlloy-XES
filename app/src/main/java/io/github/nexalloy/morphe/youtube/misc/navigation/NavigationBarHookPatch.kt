package io.github.nexalloy.morphe.youtube.misc.navigation

import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.morphe.extension.shared.ResourceType
import app.morphe.extension.shared.ResourceUtils
import app.morphe.extension.shared.Utils
import app.morphe.extension.youtube.patches.VersionCheckPatch
import app.morphe.extension.youtube.shared.NavigationBar
import io.github.nexalloy.createProxy
import io.github.nexalloy.enumValueOf
import io.github.nexalloy.morphe.youtube.misc.backgesture.addBackPressedHook
import io.github.nexalloy.morphe.youtube.misc.backgesture.addPredictiveBackGestureHook
import io.github.nexalloy.morphe.youtube.misc.backgesture.backGesturePatch
import io.github.nexalloy.morphe.youtube.misc.playservice.VersionCheck
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_43_or_greater
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook
import org.luckypray.dexkit.wrap.DexMethod

private val navigationButtonCreatedHooks = mutableListOf<(NavigationBar.NavigationButton, View) -> Unit>()
private val bottomBarContainerHooks = mutableListOf<(View) -> Unit>()

fun addBottomBarContainerHook(hook: (View) -> Unit) {
    bottomBarContainerHooks.add(hook)
}

fun hookNavigationButtonCreated(hook: (button: NavigationBar.NavigationButton, view: View) -> Unit) {
    navigationButtonCreatedHooks.add(hook)
}

fun onNavigationTabCreated(button: NavigationBar.NavigationButton, tabView: View) {
    navigationButtonCreatedHooks.forEach { it(button, tabView) }
}

val NavigationBarHook = patch(
    description = "Hooks the active navigation or search bar.",
) {
    dependsOn(backGesturePatch, VersionCheck)

    // Hook the current navigation bar enum value. Note, the 'You' tab does not have an enum value.
    InitializeButtonsFingerprint.hookMethod(scopedHook(::getNavigationEnumMethod.member) {
        after { NavigationBar.setLastAppNavigationEnum(it.result as Enum<*>) }
    })

    // Hook the creation of navigation tab views.
    if (!is_21_43_or_greater) {
        InitializeButtonsFingerprint.hookMethod(scopedHook(::pivotBarButtonsCreateDrawableViewFingerprint.member) {
            after { NavigationBar.navigationTabLoaded(it.result as View) }
        })
    }

//    if (!is_20_28_or_greater) {
//        // TODO
//    }

    InitializeButtonsFingerprint.hookMethod(
        scopedHook(
            PivotBarButtonsCreateResourceStyledViewFingerprint.member
        ) {
            after { NavigationBar.navigationImageResourceTabLoaded(it.result as View) }
        })

    InitializeBottomBarContainerFingerprint.hookMethod(scopedHook(
        ::bottomBarContainerListenerMethod.method
    ) {
        val bottomBarContainerId = ResourceUtils.getIdIdentifier("bottom_bar_container")
        before {
            val container = it.thisObject as View
            if (innerDepth == 0 && container.id == bottomBarContainerId) {
                bottomBarContainerHooks.forEach { hook -> hook(container) }
            }
        }
    })

    val selectedTab = ThreadLocal<View>()
    PivotBarButtonsViewSetSelectedFingerprint.hookMethod {
        before { selectedTab.remove() }
        after { selectedTab.get()?.let { NavigationBar.navigationTabSelected(it, true) } }
    }

    PivotBarButtonsViewSetSelectedFingerprint.hookMethod(scopedHook(::pivotBarButtonsViewSetSelectedSubFingerprint.member) {
        after {
            // `setSelect` dispatch to subviews, we need to wait for the root view.
            val isSelected = it.args[0] as Boolean
            if (isSelected) {
                selectedTab.set(it.thisObject as View)
            }
        }
    })

    // Hook onto navigation bar touches. Needed to detect a tap on the selected
    // navigation button closes the search, as the button is not selected again.
    PivotBarDispatchTouchEventFingerprint.hookMethod {
        before {
            NavigationBar.navigationBarTouched(it.args[0] as? MotionEvent)
        }
    }

    // Hook onto back button pressed.  Needed to fix race problem with
    // Litho filtering based on navigation tab before the tab is updated.
    addBackPressedHook(NavigationBar::onBackPressed)
    addPredictiveBackGestureHook(NavigationBar::onBackInvoked)

    // Hook the search bar.
    DexMethod("Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;)Landroid/view/View;").hookMethod {
        after {
            val layout = Utils.getContext().resources.getResourceName(it.args[0] as Int)
            if (layout.contains("/action_bar_search_results_view_")) {
                NavigationBar.searchBarResultsViewLoaded(it.result as View)
            }
        }
    }

    // Hook the back button visibility.
    ToolbarLayoutFingerprint.hookMethod(scopedHook(DexMethod("Landroid/view/ViewGroup;->findViewById(I)Landroid/view/View;").toMember()) {
        val appCompatToolbarClass =
            classLoader.loadClass(AppCompatToolbarBackButtonFingerprint.dexMethod.className)
        val getNavigationIcon = AppCompatToolbarBackButtonFingerprint.method
        val toolbarContainerId = toolbarContainerId
        after {
            if (it.args[0] != toolbarContainerId) return@after
            val layout = it.result as FrameLayout
            val toolbar = Utils.getChildView<View>(
                layout, false
            ) { it: View -> appCompatToolbarClass.isAssignableFrom(it.javaClass) }
            NavigationBar.setToolbar(toolbar.createProxy { impl ->
                NavigationBar.AppCompatToolbarPatchInterface {
                    getNavigationIcon(impl.get()) as Drawable?
                }
            })
        }
    })

    // Fix YT bug of notification tab missing the filled icon.
    val tabActivityCairo = ::navigationEnumClass.clazz.enumValueOf("TAB_ACTIVITY_CAIRO")
    if (tabActivityCairo != null) {
        ::getNavIconResIdFingerprint.dexMethodList.forEach {
            it.hookMethod {
                val fillBellCairoBlack = ResourceUtils.getIdentifier(
                    ResourceType.DRAWABLE,
                    if (VersionCheckPatch.IS_20_31_OR_GREATER)
                        "yt_fill_experimental_bell_vd_theme_24"
                    else
                        "morphe_fill_bell_cairo_black_24"
                )
                after {
                    val navEnum = it.args[0] as Enum<*>
                    val selected = it.args[1] as Boolean
                    if (navEnum == tabActivityCairo && selected)
                        it.result = fillBellCairoBlack
                }
            }
        }
    }
}
