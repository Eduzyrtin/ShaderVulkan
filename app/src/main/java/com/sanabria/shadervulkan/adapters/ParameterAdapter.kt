package com.sanabria.shadervulkan.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sanabria.shadervulkan.R
import com.sanabria.shadervulkan.model.ShaderParameter
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class ParameterAdapter(
    private val parameters: List<ShaderParameter>,
    private val favorites: MutableSet<String> = mutableSetOf(),
    /** Marca o desmarca un favorito (T74); devuelve si queda marcado. null = sin estrellas. */
    private val onFavorite: ((String) -> Boolean)? = null,
    private val onParameterChanged: (String, Float) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txt_param_name)
        val txtValue: TextView = view.findViewById(R.id.txt_param_value)
        val slider: Slider = view.findViewById(R.id.slider_param)
        val txtFav: TextView = view.findViewById(R.id.txt_param_fav)
        val txtReset: TextView = view.findViewById(R.id.txt_param_reset)
    }

    /** Último valor puesto por el usuario en esta sesión de la lista (por nombre de parámetro). */
    private val live = HashMap<String, Float>()

    /** Fila informativa: etiquetas y separadores del shader (#pragma parameter sin rango editable). */
    class InfoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtInfo: TextView = view.findViewById(R.id.txt_param_info)
        val divider: View = view.findViewById(R.id.divider_param_info)
    }

    override fun getItemViewType(position: Int) = if (parameters[position].isInfo) TYPE_INFO else TYPE_SLIDER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_INFO) {
            InfoViewHolder(inflater.inflate(R.layout.item_parameter_info, parent, false))
        } else {
            ViewHolder(inflater.inflate(R.layout.item_parameter, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val param = parameters[position]
        if (holder is InfoViewHolder) {
            bindInfo(holder, param)
        } else if (holder is ViewHolder) {
            bindSlider(holder, param)
        }
    }

    private fun bindInfo(holder: InfoViewHolder, param: ShaderParameter) {
        holder.divider.visibility = if (param.isDivider) View.VISIBLE else View.GONE
        holder.txtInfo.visibility = if (param.isDivider) View.GONE else View.VISIBLE
        // Una etiqueta en blanco es solo un hueco entre grupos
        holder.txtInfo.text = if (param.isSpacer) " " else param.description.trim()
    }

    private fun bindSlider(holder: ViewHolder, param: ShaderParameter) {
        holder.txtName.text = param.title
        holder.txtFav.visibility = if (onFavorite == null) View.GONE else View.VISIBLE
        holder.txtFav.text = if (param.name in favorites) "[*]" else "[ ]"
        holder.txtFav.setOnClickListener {
            val fav = onFavorite?.invoke(param.name) ?: return@setOnClickListener
            if (fav) favorites.add(param.name) else favorites.remove(param.name)
            holder.txtFav.text = if (fav) "[*]" else "[ ]"
        }

        // Los ViewHolder se reciclan: hay que quitar el listener del parámetro anterior antes de cambiar rango/valor,
        // o se seguirían notificando cambios de los parámetros que ese slider mostró antes.
        holder.slider.clearOnChangeListeners()

        // El slider trabaja con el ÍNDICE del paso (0..stepCount, de uno en uno) y no con el valor: Material Slider exige que el
        // rango sea múltiplo exacto del paso y que el valor caiga en la rejilla, y con decimales en coma flotante eso falla en
        // cuanto hay muchos pasos (rango 0.16–1024 con paso 0.1 daba un paso 0.10000391 y cerraba la app). Con enteros no hay
        // error posible. El valor real sale de param.valueAt(índice). (Los de rango degenerado son informativos y no llegan
        // aquí: ver ShaderParameter.isInfo.)
        holder.slider.isEnabled = true
        holder.slider.valueFrom = 0f
        holder.slider.valueTo = param.stepCount.toFloat()
        holder.slider.stepSize = 1f
        // 'param' es inmutable: el valor vivo se recuerda aquí para que al reciclar la fila no vuelva al inicial
        val shown = live[param.name] ?: param.current
        holder.slider.value = param.indexOf(shown).toFloat()
        holder.txtValue.text = param.format(param.valueAt(param.indexOf(shown)))

        holder.slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val snapped = param.valueAt(value.roundToInt())
                holder.txtValue.text = param.format(snapped)
                live[param.name] = snapped
                onParameterChanged(param.name, snapped)
            }
        }

        holder.txtReset.setOnClickListener {
            val idx = param.indexOf(param.initial)
            val value = param.valueAt(idx)
            holder.slider.value = idx.toFloat()
            holder.txtValue.text = param.format(value)
            live[param.name] = value
            onParameterChanged(param.name, value)
        }
    }

    override fun getItemCount() = parameters.size

    private companion object {
        const val TYPE_SLIDER = 0
        const val TYPE_INFO = 1
    }
}
