package com.example.ali.blemanager

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.example.ali.myapplication.BLEEvent
import com.example.ali.myapplication.BLEState
import com.example.ali.myapplication.DeviceValues
import com.polidea.rxandroidble2.RxBleClient
import com.polidea.rxandroidble2.RxBleConnection
import com.polidea.rxandroidble2.RxBleDevice
import com.polidea.rxandroidble2.scan.ScanFilter
import com.polidea.rxandroidble2.scan.ScanResult
import com.polidea.rxandroidble2.scan.ScanSettings
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Created by ali on 5/28/2018.
 */
class BleManager {


    companion object {

        val KEY_BLE_NOTIFY_CHARACTERISTICS_UUID = "0000fff4-0000-1000-8000-00805f9b34fb"
        val KEY_BLE_STOP_START_CHARACTERISTICS_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
        val KEY_BLE_CONFIG_CHARACTERISTICS_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        val KEY_BLE_MAIN_SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"

        private lateinit var rxBleClient: RxBleClient
        private var scanDisposable: Disposable? = null
        private var selectedDevice: RxBleDevice? = null
        private var notifyCharac: BluetoothGattCharacteristic? = null
        private val timer = Observable.interval(0, 20, TimeUnit.SECONDS)
        private var connection: RxBleConnection? = null
        private var connectionDisposable: Disposable? = null
        private var laserDisposable: Disposable? = null
        private var notificationDisposable: Disposable? = null
        private var isDeviceBonded = false
        private lateinit var appContext: Context
        private var state: BLEState = BLEState.initial()


        fun BLEState.Companion.reduce(state: BLEState, event: BLEEvent): BLEState {
            var newState = state.copy()
            when (event) {
                is BLEEvent.Scan -> {
                    newState.isScanning = true
                }
                is BLEEvent.Connect -> {
                    newState.connectionState = BLEState.ConnectionState.connecting
                }
                is BLEEvent.DiscoverServices -> {
                    newState.isSearchingServices = true
                }
                is BLEEvent.FoundServices -> {
                    newState.isSearchingServices = false
                }
                is BLEEvent.FindNotifyCharacteristic -> {
                    newState.isSearchingServices = true
                }
                is BLEEvent.FoundNotifyCharacateristic -> {
                    newState.hasNotifyCharacteristic = true
                }
                is BLEEvent.Bonded -> {
                    newState.bondingState = BLEState.BondingState.bonded
                }
                is BLEEvent.ReadNotification -> {
                    newState.deviceValues = event.deviceValues
                }
                is BLEEvent.Disconnect -> {
                    newState.connectionState = BLEState.ConnectionState.disconnecting
                }
                is BLEEvent.Disconnected -> {
                    newState.connectionState = BLEState.ConnectionState.disconnected
                }
                is BLEEvent.FinishedScanning -> {
                    newState.isScanning = false
                }
            }
            return newState
        }


        @JvmStatic
        fun init(appContext: Context): RxBleClient {
            BleManager.appContext = appContext
            state = BLEState.initial()
            return RxBleClient.create(appContext)
        }


        //todo implement init that auto-selects a previously connected ble device

        fun startScan(): Observable<ScanResult> {
            return App.rxBleClient.scanBleDevices(
                    ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                            .build(),
                    ScanFilter.Builder()
                            .build()
            )
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .takeUntil(Observable.timer(5, TimeUnit.SECONDS))
        }

        @JvmStatic
        fun connect(device: RxBleDevice): Observable<BluetoothGattService> {
            return device.establishConnection(false)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnNext { connection ->
                        Log.d("BLE", "made connection")
                        selectedDevice = device
                        BleManager.connection = connection
                    }
                    .flatMap { connection ->
                        Log.d("BLE", "discovering services")
                        connection.discoverServices().toObservable()
                    }.flatMap { services ->
                        Log.d("BLE", "discovered services")
                        services.getService(UUID.fromString(KEY_BLE_MAIN_SERVICE_UUID)).toObservable()
                    }
                    .doOnNext { service ->
                        Log.d("BLE", "getting notify characteristic")
                        notifyCharac = service.getCharacteristic(UUID.fromString(KEY_BLE_NOTIFY_CHARACTERISTICS_UUID))
                    }
                    .doOnSubscribe {
                        connectionDisposable = it
                    }

        }

        fun disconnect() {
            if (connectionDisposable?.isDisposed == false) {
                connectionDisposable?.dispose()
            }
        }


        private fun startNotifications() {
            val conn = connection
            if (conn != null) {
                notifyCharac?.let {
                    conn.setupNotification(it)
                            .subscribeOn(Schedulers.io())
                            .doOnNext { Log.d("BLE", "subscribed to notifications !") }
                            .flatMap { notificationObservable -> notificationObservable }
                            .observeOn(AndroidSchedulers.mainThread())
                            .doFinally { Log.d("BLE", "no more data!") }
                            .doOnSubscribe {
                                notificationDisposable = it
                            }
                            .subscribe(
                                    { bytes ->
                                        val deviceValues = DeviceValues(bytes)
                                        Log.d("BLE", "received data")
                                    },
                                    { t: Throwable? ->

                                    }
                            )
                }
            }
        }

        private fun startTurnLaserOnCycle() {
            timer.observeOn(Schedulers.io())
                    .doOnSubscribe {
                        laserDisposable = it
                    }
                    .subscribe(
                            {
                                turnLasterOn()
                            },
                            { t: Throwable? ->


                            }
                    )
        }

        fun startReading() {
            startNotifications()
            startTurnLaserOnCycle()
        }

        fun turnLasterOn() {
            val bytesToWrite = byteArrayOf(1)
            connection?.writeCharacteristic(UUID.fromString(KEY_BLE_STOP_START_CHARACTERISTICS_UUID), bytesToWrite)
                    ?.subscribe({}, {})
        }

        fun stopReading() {
            val bytesToWrite = byteArrayOf(0)
            connection?.writeCharacteristic(UUID.fromString(KEY_BLE_STOP_START_CHARACTERISTICS_UUID), bytesToWrite)
                    ?.subscribe({}, {})

            if (laserDisposable?.isDisposed == false) {
                laserDisposable?.dispose()
            }

            if (notificationDisposable?.isDisposed == false) {
                notificationDisposable?.dispose()
            }
        }

        fun monitorBleConnection(rxBleDevice: RxBleDevice) {
            rxBleDevice.observeConnectionStateChanges()
                    .subscribe(
                            { state ->
                                if (state != null) {
                                    Log.d("BLE", state.toString())
                                }
                            },
                            { t: Throwable? -> }
                    )

        }


        fun bondingChanged(rxBleDevice: RxBleDevice, extras: Bundle): Int {
            val state = extras.getInt(BluetoothDevice.EXTRA_BOND_STATE)
            val detectedDevice = extras.get(BluetoothDevice.EXTRA_DEVICE) as BluetoothDevice
            if (detectedDevice.address == rxBleDevice.macAddress) {
                if (detectedDevice.address == detectedDevice.address) {
                    return state
                }
            }
            return -1
        }

    }
}