package za.ac.richfield.fnr.ui

import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.viewbinding.ViewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import za.ac.richfield.fnr.AppContainer
import za.ac.richfield.fnr.FnrApp
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.util.UserFacingException

/** Base class: view binding set up in onViewCreated and cleared in onDestroyView. */
abstract class BaseFragment<B : ViewBinding>(
    @LayoutRes layout: Int,
    private val bind: (View) -> B,
) : Fragment(layout) {
    private var _b: B? = null
    protected val b: B get() = _b!!

    protected val container: AppContainer get() = (requireActivity().application as FnrApp).container

    final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _b = bind(view)
        setup(savedInstanceState)
    }

    abstract fun setup(savedInstanceState: Bundle?)

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    /** Collects a Flow only while the screen is visible. */
    protected fun <T> observe(flow: Flow<T>, action: suspend (T) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { action(it) }
            }
        }
    }

    /**
     * Runs an action and shows a friendly message on failure, so the app never crashes
     * on bad input or a network error (NFR-REL-01, NFR-REL-02).
     */
    protected fun launchSafely(onError: ((String) -> Unit)? = null, block: suspend CoroutineScope.() -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: UserFacingException) {
                onError?.invoke(e.message.orEmpty()) ?: toast(e.message.orEmpty())
            } catch (e: SecurityException) {
                toast("You do not have access to that.")
            } catch (e: Exception) {
                onError?.invoke("Something went wrong: ${e.message}") ?: toast("Something went wrong: ${e.message}")
            }
        }
    }

    protected fun toast(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_LONG).show() }
    }

    protected fun confirm(title: String, message: String, action: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(action) { _, _ -> onConfirm() }
            .show()
    }

    /** Navigates and clears the whole back stack (used after login and logout). */
    protected fun navigateClearing(destination: Int, args: Bundle? = null) {
        findNavController().navigate(
            destination, args,
            NavOptions.Builder().setPopUpTo(R.id.nav_graph, true).build(),
        )
    }

    protected fun showError(layout: TextInputLayout, error: String?) {
        layout.error = error
        layout.isErrorEnabled = error != null
    }

    protected fun View.show(visible: Boolean) { isVisible = visible }
}

/** Creates a ViewModel with access to the app's repositories. */
inline fun <reified VM : ViewModel> Fragment.appViewModels(noinline factory: (AppContainer) -> VM) =
    viewModels<VM> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return factory((requireActivity().application as FnrApp).container) as T
            }
        }
    }
