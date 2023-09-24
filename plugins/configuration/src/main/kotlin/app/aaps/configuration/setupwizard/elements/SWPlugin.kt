package app.aaps.configuration.setupwizard.elements

import android.widget.LinearLayout
import androidx.annotation.StringRes
import app.aaps.configuration.configBuilder.events.EventConfigBuilderUpdateGui
import app.aaps.configuration.setupwizard.SWDefinition
import app.aaps.interfaces.configuration.ConfigBuilder
import app.aaps.interfaces.plugin.ActivePlugin
import app.aaps.interfaces.plugin.PluginType
import app.aaps.interfaces.rx.AapsSchedulers
import app.aaps.interfaces.ui.UiInteraction
import dagger.android.HasAndroidInjector
import info.nightscout.core.utils.fabric.FabricPrivacy
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.security.InvalidParameterException
import javax.inject.Inject

class SWPlugin(injector: HasAndroidInjector, private val definition: SWDefinition) : SWItem(injector, Type.PLUGIN) {

    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var configBuilder: ConfigBuilder
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private val disposable = CompositeDisposable()
    private val pluginViewHolders = ArrayList<ConfigBuilder.PluginViewHolderInterface>()
    private var pType: PluginType? = null
    @StringRes private var pluginDescription = 0

    // TODO: Adrian how to clear disposable in this case?
    init {
        disposable += rxBus
            .toObservable(EventConfigBuilderUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ for (pluginViewHolder in pluginViewHolders) pluginViewHolder.update() }, fabricPrivacy::logException)
    }

    fun option(pType: PluginType, @StringRes pluginDescription: Int): SWPlugin {
        this.pType = pType
        this.pluginDescription = pluginDescription
        return this
    }

    override fun generateDialog(layout: LinearLayout) {
        val pType = this.pType ?: throw InvalidParameterException()
        configBuilder.createViewsForPlugins(
            title = null,
            description = pluginDescription,
            pluginType = pType,
            plugins = activePlugin.getSpecificPluginsVisibleInList(pType),
            pluginViewHolders = pluginViewHolders,
            activity = definition.activity,
            parent = layout
        )
        super.generateDialog(layout)
    }
}