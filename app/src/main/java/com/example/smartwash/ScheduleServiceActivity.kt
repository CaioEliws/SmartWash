package com.example.smartwash

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import android.util.Log

class ScheduleServiceActivity : AppCompatActivity() {
    private lateinit var serviceSpinner: Spinner
    private lateinit var dateButton: Button
    private lateinit var timeButton: Button
    private lateinit var submitButton: Button
    private lateinit var selectedDateText: TextView
    private lateinit var selectedTimeText: TextView

    private var selectedService: Service? = null
    private var selectedDate: Calendar? = null
    private var selectedTime: Calendar? = null

    private val client = OkHttpClient()
    private val mockToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzbWFydHdhc2giLCJzdWIiOiJlZHVhcmRvMkBnbWFpbC5jb20iLCJleHAiOjE3NDg0NzgzNDB9.DDDnbxiOQo4VavKy_pQidBjoBEhbADRjsrrsgG5JSqE"
    private val mockMachineId = "3b530fc9-ec43-4670-85d6-455926c41683"

    private var serviceList: List<Service> = emptyList()
    private var availableHours: List<String> = emptyList()
    private var selectedHour: String? = null

    data class Service(val name: String, val duration: Int, val id: String) // duration in minutes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_service)

        serviceSpinner = findViewById(R.id.serviceSpinner)
        dateButton = findViewById(R.id.dateButton)
        timeButton = findViewById(R.id.timeButton)
        submitButton = findViewById(R.id.submitButton)
        selectedDateText = findViewById(R.id.selectedDateText)
        selectedTimeText = findViewById(R.id.selectedTimeText)

        timeButton.isEnabled = false

        loadServices()

        serviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View, position: Int, id: Long) {
                selectedService = if (serviceList.isNotEmpty()) serviceList[position] else null
                checkFetchAvailableHours()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        dateButton.setOnClickListener {
            val now = Calendar.getInstance()
            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                val cal = Calendar.getInstance()
                cal.set(year, month, dayOfMonth)
                selectedDate = cal
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                selectedDateText.text = sdf.format(cal.time)
                checkFetchAvailableHours()
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
        }

        timeButton.setOnClickListener {
            if (availableHours.isEmpty()) {
                Toast.makeText(this, "Nenhum horário disponível", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val builder = android.app.AlertDialog.Builder(this)
            builder.setTitle("Selecione o horário")
            builder.setItems(availableHours.toTypedArray()) { _, which ->
                selectedHour = availableHours[which]
                selectedTimeText.text = selectedHour
            }
            builder.show()
        }

        submitButton.setOnClickListener {
            if (selectedService == null || selectedDate == null || selectedHour == null) {
                Toast.makeText(this, "Por favor, selecione todos os campos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val date = selectedDate!!
            val service = selectedService!!
            // Combine selected date and hour to ISO string for startTime
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date.time)
            val startTimeStr = "$dateStr" + "T" + selectedHour + ":00"
            // Calculate end time
            val cal = Calendar.getInstance()
            cal.time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(startTimeStr)!!
            cal.add(Calendar.MINUTE, service.duration)
            val endTimeStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(cal.time)
            createOrder(mockMachineId, service.id, startTimeStr, endTimeStr)
        }
    }

    private fun loadServices() {
        CoroutineScope(Dispatchers.IO).launch {
            val request = Request.Builder()
                .url("http://10.0.2.2:8090/services")
                .addHeader("Authorization", "Bearer $mockToken")
                .build()
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val jsonArray = JSONArray(body)
                    val services = mutableListOf<Service>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        services.add(Service(
                            name = obj.getString("name"),
                            duration = obj.getInt("duration"),
                            id = obj.getString("id")
                        ))
                    }
                    serviceList = services
                    withContext(Dispatchers.Main) {
                        val serviceNames = services.map { "${it.name} (${it.duration} min)" }
                        val adapter = ArrayAdapter(this@ScheduleServiceActivity, android.R.layout.simple_spinner_item, serviceNames)
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        serviceSpinner.adapter = adapter
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScheduleServiceActivity, "Erro ao carregar serviços", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ScheduleService", "Erro de conexão", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScheduleServiceActivity, "Erro de conexão", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkFetchAvailableHours() {
        if (selectedService != null && selectedDate != null) {
            fetchAvailableHours(mockMachineId, selectedService!!.id, selectedDate)
        } else {
            availableHours = emptyList()
            selectedHour = null
            selectedTimeText.text = "Nenhum horário selecionado"
            timeButton.isEnabled = false
        }
    }

    private fun fetchAvailableHours(machineId: String, serviceId: String?, date: Calendar?) {
        if (serviceId == null || date == null) return
        val dateIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(date.time)
        val json = JSONObject()
        json.put("serviceId", serviceId)
        json.put("date", dateIso)
        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        val request = Request.Builder()
            .url("http://10.0.2.2:8090/machines/${machineId}/available-hours")
            .addHeader("Authorization", "Bearer $mockToken")
            .post(body)
            .build()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val jsonArray = JSONArray(responseBody)
                    val hours = mutableListOf<String>()
                    for (i in 0 until jsonArray.length()) {
                        hours.add(jsonArray.getString(i))
                    }
                    availableHours = hours
                    withContext(Dispatchers.Main) {
                        timeButton.isEnabled = hours.isNotEmpty()
                        selectedHour = null
                        selectedTimeText.text = "Nenhum horário selecionado"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        availableHours = emptyList()
                        timeButton.isEnabled = false
                        selectedHour = null
                        selectedTimeText.text = "Nenhum horário selecionado"
                        Toast.makeText(this@ScheduleServiceActivity, "Erro ao buscar horários disponíveis", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ScheduleService", "Erro de conexão", e)
                withContext(Dispatchers.Main) {
                    availableHours = emptyList()
                    timeButton.isEnabled = false
                    selectedHour = null
                    selectedTimeText.text = "Nenhum horário selecionado"
                    Toast.makeText(this@ScheduleServiceActivity, "Erro de conexão ao buscar horários", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Example function for creating an order (not yet wired to UI)
    private fun createOrder(machineId: String, serviceId: String, startTime: String, endTime: String) {
        val json = JSONObject()
        json.put("machineId", machineId)
        json.put("serviceId", serviceId)
        json.put("startTime", startTime)
        json.put("endTime", endTime)
        val body = RequestBody.create("application/json".toMediaType(), json.toString())
        val request = Request.Builder()
            .url("http://10.0.2.2:8090/orders")
            .addHeader("Authorization", "Bearer $mockToken")
            .post(body)
            .build()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScheduleServiceActivity, "Agendamento realizado com sucesso!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScheduleServiceActivity, "Erro ao agendar serviço", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ScheduleService", "Erro de conexão", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScheduleServiceActivity, "Erro de conexão ao agendar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
} 