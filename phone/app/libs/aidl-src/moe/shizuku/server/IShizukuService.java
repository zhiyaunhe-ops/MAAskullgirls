/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package moe.shizuku.server;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
public interface IShizukuService extends android.os.IInterface
{
  /** Default implementation for IShizukuService. */
  public static class Default implements moe.shizuku.server.IShizukuService
  {
    @Override public int getVersion() throws android.os.RemoteException
    {
      return 0;
    }
    @Override public int getUid() throws android.os.RemoteException
    {
      return 0;
    }
    @Override public int checkPermission(java.lang.String permission) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public moe.shizuku.server.IRemoteProcess newProcess(java.lang.String[] cmd, java.lang.String[] env, java.lang.String dir) throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.lang.String getSELinuxContext() throws android.os.RemoteException
    {
      return null;
    }
    @Override public java.lang.String getSystemProperty(java.lang.String name, java.lang.String defaultValue) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void setSystemProperty(java.lang.String name, java.lang.String value) throws android.os.RemoteException
    {
    }
    @Override public int addUserService(moe.shizuku.server.IShizukuServiceConnection conn, Bundle args) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public int removeUserService(moe.shizuku.server.IShizukuServiceConnection conn, Bundle args) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public void requestPermission(int requestCode) throws android.os.RemoteException
    {
    }
    @Override public boolean checkSelfPermission() throws android.os.RemoteException
    {
      return false;
    }
    @Override public boolean shouldShowRequestPermissionRationale() throws android.os.RemoteException
    {
      return false;
    }
    @Override public void attachApplication(moe.shizuku.server.IShizukuApplication application, Bundle args) throws android.os.RemoteException
    {
    }
    @Override public void exit() throws android.os.RemoteException
    {
    }
    @Override public void attachUserService(android.os.IBinder binder, Bundle options) throws android.os.RemoteException
    {
    }
    @Override public void dispatchPackageChanged(Intent intent) throws android.os.RemoteException
    {
    }
    @Override public boolean isHidden(int uid) throws android.os.RemoteException
    {
      return false;
    }
    @Override public void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, Bundle data) throws android.os.RemoteException
    {
    }
    @Override public int getFlagsForUid(int uid, int mask) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public void updateFlagsForUid(int uid, int mask, int value) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements moe.shizuku.server.IShizukuService
  {
    private static final java.lang.String DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an moe.shizuku.server.IShizukuService interface,
     * generating a proxy if needed.
     */
    public static moe.shizuku.server.IShizukuService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof moe.shizuku.server.IShizukuService))) {
        return ((moe.shizuku.server.IShizukuService)iin);
      }
      return new moe.shizuku.server.IShizukuService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      switch (code)
      {
        case INTERFACE_TRANSACTION:
        {
          reply.writeString(descriptor);
          return true;
        }
        case TRANSACTION_getVersion:
        {
          data.enforceInterface(descriptor);
          int _result = this.getVersion();
          reply.writeNoException();
          reply.writeInt(_result);
          return true;
        }
        case TRANSACTION_getUid:
        {
          data.enforceInterface(descriptor);
          int _result = this.getUid();
          reply.writeNoException();
          reply.writeInt(_result);
          return true;
        }
        case TRANSACTION_checkPermission:
        {
          data.enforceInterface(descriptor);
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _result = this.checkPermission(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          return true;
        }
        case TRANSACTION_newProcess:
        {
          data.enforceInterface(descriptor);
          java.lang.String[] _arg0;
          _arg0 = data.createStringArray();
          java.lang.String[] _arg1;
          _arg1 = data.createStringArray();
          java.lang.String _arg2;
          _arg2 = data.readString();
          moe.shizuku.server.IRemoteProcess _result = this.newProcess(_arg0, _arg1, _arg2);
          reply.writeNoException();
          reply.writeStrongBinder((((_result!=null))?(_result.asBinder()):(null)));
          return true;
        }
        case TRANSACTION_getSELinuxContext:
        {
          data.enforceInterface(descriptor);
          java.lang.String _result = this.getSELinuxContext();
          reply.writeNoException();
          reply.writeString(_result);
          return true;
        }
        case TRANSACTION_getSystemProperty:
        {
          data.enforceInterface(descriptor);
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          java.lang.String _result = this.getSystemProperty(_arg0, _arg1);
          reply.writeNoException();
          reply.writeString(_result);
          return true;
        }
        case TRANSACTION_setSystemProperty:
        {
          data.enforceInterface(descriptor);
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          this.setSystemProperty(_arg0, _arg1);
          reply.writeNoException();
          return true;
        }
        case TRANSACTION_addUserService:
        {
          data.enforceInterface(descriptor);
          moe.shizuku.server.IShizukuServiceConnection _arg0;
          _arg0 = moe.shizuku.server.IShizukuServiceConnection.Stub.asInterface(data.readStrongBinder());
          Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          int _result = this.addUserService(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(_result);
          return true;
        }
        case TRANSACTION_removeUserService:
        {
          data.enforceInterface(descriptor);
          moe.shizuku.server.IShizukuServiceConnection _arg0;
          _arg0 = moe.shizuku.server.IShizukuServiceConnection.Stub.asInterface(data.readStrongBinder());
          Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          int _result = this.removeUserService(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(_result);
          return true;
        }
        case TRANSACTION_requestPermission:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          this.requestPermission(_arg0);
          reply.writeNoException();
          return true;
        }
        case TRANSACTION_checkSelfPermission:
        {
          data.enforceInterface(descriptor);
          boolean _result = this.checkSelfPermission();
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          return true;
        }
        case TRANSACTION_shouldShowRequestPermissionRationale:
        {
          data.enforceInterface(descriptor);
          boolean _result = this.shouldShowRequestPermissionRationale();
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          return true;
        }
        case TRANSACTION_attachApplication:
        {
          data.enforceInterface(descriptor);
          moe.shizuku.server.IShizukuApplication _arg0;
          _arg0 = moe.shizuku.server.IShizukuApplication.Stub.asInterface(data.readStrongBinder());
          Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          this.attachApplication(_arg0, _arg1);
          reply.writeNoException();
          return true;
        }
        case TRANSACTION_exit:
        {
          data.enforceInterface(descriptor);
          this.exit();
          reply.writeNoException();
          return true;
        }
        case TRANSACTION_attachUserService:
        {
          data.enforceInterface(descriptor);
          android.os.IBinder _arg0;
          _arg0 = data.readStrongBinder();
          Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          this.attachUserService(_arg0, _arg1);
          reply.writeNoException();
          return true;
        }
        case TRANSACTION_dispatchPackageChanged:
        {
          data.enforceInterface(descriptor);
          Intent _arg0;
          if ((0!=data.readInt())) {
            _arg0 = Intent.CREATOR.createFromParcel(data);
          }
          else {
            _arg0 = null;
          }
          this.dispatchPackageChanged(_arg0);
          return true;
        }
        case TRANSACTION_isHidden:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          boolean _result = this.isHidden(_arg0);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          return true;
        }
        case TRANSACTION_dispatchPermissionConfirmationResult:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          Bundle _arg3;
          if ((0!=data.readInt())) {
            _arg3 = Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg3 = null;
          }
          this.dispatchPermissionConfirmationResult(_arg0, _arg1, _arg2, _arg3);
          return true;
        }
        case TRANSACTION_getFlagsForUid:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          int _result = this.getFlagsForUid(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(_result);
          return true;
        }
        case TRANSACTION_updateFlagsForUid:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          this.updateFlagsForUid(_arg0, _arg1, _arg2);
          reply.writeNoException();
          return true;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
    }
    private static class Proxy implements moe.shizuku.server.IShizukuService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public int getVersion() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getVersion, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getVersion();
          }
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public int getUid() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getUid, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getUid();
          }
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public int checkPermission(java.lang.String permission) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(permission);
          boolean _status = mRemote.transact(Stub.TRANSACTION_checkPermission, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().checkPermission(permission);
          }
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public moe.shizuku.server.IRemoteProcess newProcess(java.lang.String[] cmd, java.lang.String[] env, java.lang.String dir) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        moe.shizuku.server.IRemoteProcess _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStringArray(cmd);
          _data.writeStringArray(env);
          _data.writeString(dir);
          boolean _status = mRemote.transact(Stub.TRANSACTION_newProcess, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().newProcess(cmd, env, dir);
          }
          _reply.readException();
          _result = moe.shizuku.server.IRemoteProcess.Stub.asInterface(_reply.readStrongBinder());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.lang.String getSELinuxContext() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getSELinuxContext, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getSELinuxContext();
          }
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public java.lang.String getSystemProperty(java.lang.String name, java.lang.String defaultValue) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(name);
          _data.writeString(defaultValue);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getSystemProperty, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getSystemProperty(name, defaultValue);
          }
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void setSystemProperty(java.lang.String name, java.lang.String value) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(name);
          _data.writeString(value);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setSystemProperty, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().setSystemProperty(name, value);
            return;
          }
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public int addUserService(moe.shizuku.server.IShizukuServiceConnection conn, Bundle args) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((conn!=null))?(conn.asBinder()):(null)));
          if ((args!=null)) {
            _data.writeInt(1);
            args.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_addUserService, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().addUserService(conn, args);
          }
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public int removeUserService(moe.shizuku.server.IShizukuServiceConnection conn, Bundle args) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((conn!=null))?(conn.asBinder()):(null)));
          if ((args!=null)) {
            _data.writeInt(1);
            args.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_removeUserService, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().removeUserService(conn, args);
          }
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void requestPermission(int requestCode) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(requestCode);
          boolean _status = mRemote.transact(Stub.TRANSACTION_requestPermission, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().requestPermission(requestCode);
            return;
          }
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public boolean checkSelfPermission() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_checkSelfPermission, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().checkSelfPermission();
          }
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean shouldShowRequestPermissionRationale() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_shouldShowRequestPermissionRationale, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().shouldShowRequestPermissionRationale();
          }
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void attachApplication(moe.shizuku.server.IShizukuApplication application, Bundle args) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder((((application!=null))?(application.asBinder()):(null)));
          if ((args!=null)) {
            _data.writeInt(1);
            args.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_attachApplication, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().attachApplication(application, args);
            return;
          }
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void exit() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_exit, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().exit();
            return;
          }
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void attachUserService(android.os.IBinder binder, Bundle options) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder(binder);
          if ((options!=null)) {
            _data.writeInt(1);
            options.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_attachUserService, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().attachUserService(binder, options);
            return;
          }
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void dispatchPackageChanged(Intent intent) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          if ((intent!=null)) {
            _data.writeInt(1);
            intent.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_dispatchPackageChanged, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().dispatchPackageChanged(intent);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public boolean isHidden(int uid) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(uid);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isHidden, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().isHidden(uid);
          }
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, Bundle data) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(requestUid);
          _data.writeInt(requestPid);
          _data.writeInt(requestCode);
          if ((data!=null)) {
            _data.writeInt(1);
            data.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_dispatchPermissionConfirmationResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public int getFlagsForUid(int uid, int mask) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(uid);
          _data.writeInt(mask);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getFlagsForUid, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            return getDefaultImpl().getFlagsForUid(uid, mask);
          }
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void updateFlagsForUid(int uid, int mask, int value) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(uid);
          _data.writeInt(mask);
          _data.writeInt(value);
          boolean _status = mRemote.transact(Stub.TRANSACTION_updateFlagsForUid, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().updateFlagsForUid(uid, mask, value);
            return;
          }
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      public static moe.shizuku.server.IShizukuService sDefaultImpl;
    }
    static final int TRANSACTION_getVersion = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_getUid = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_checkPermission = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_newProcess = (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
    static final int TRANSACTION_getSELinuxContext = (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_getSystemProperty = (android.os.IBinder.FIRST_CALL_TRANSACTION + 9);
    static final int TRANSACTION_setSystemProperty = (android.os.IBinder.FIRST_CALL_TRANSACTION + 10);
    static final int TRANSACTION_addUserService = (android.os.IBinder.FIRST_CALL_TRANSACTION + 11);
    static final int TRANSACTION_removeUserService = (android.os.IBinder.FIRST_CALL_TRANSACTION + 12);
    static final int TRANSACTION_requestPermission = (android.os.IBinder.FIRST_CALL_TRANSACTION + 14);
    static final int TRANSACTION_checkSelfPermission = (android.os.IBinder.FIRST_CALL_TRANSACTION + 15);
    static final int TRANSACTION_shouldShowRequestPermissionRationale = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16);
    static final int TRANSACTION_attachApplication = (android.os.IBinder.FIRST_CALL_TRANSACTION + 17);
    static final int TRANSACTION_exit = (android.os.IBinder.FIRST_CALL_TRANSACTION + 100);
    static final int TRANSACTION_attachUserService = (android.os.IBinder.FIRST_CALL_TRANSACTION + 101);
    static final int TRANSACTION_dispatchPackageChanged = (android.os.IBinder.FIRST_CALL_TRANSACTION + 102);
    static final int TRANSACTION_isHidden = (android.os.IBinder.FIRST_CALL_TRANSACTION + 103);
    static final int TRANSACTION_dispatchPermissionConfirmationResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 104);
    static final int TRANSACTION_getFlagsForUid = (android.os.IBinder.FIRST_CALL_TRANSACTION + 105);
    static final int TRANSACTION_updateFlagsForUid = (android.os.IBinder.FIRST_CALL_TRANSACTION + 106);
    public static boolean setDefaultImpl(moe.shizuku.server.IShizukuService impl) {
      // Only one user of this interface can use this function
      // at a time. This is a heuristic to detect if two different
      // users in the same process use this function.
      if (Stub.Proxy.sDefaultImpl != null) {
        throw new IllegalStateException("setDefaultImpl() called twice");
      }
      if (impl != null) {
        Stub.Proxy.sDefaultImpl = impl;
        return true;
      }
      return false;
    }
    public static moe.shizuku.server.IShizukuService getDefaultImpl() {
      return Stub.Proxy.sDefaultImpl;
    }
  }
  public int getVersion() throws android.os.RemoteException;
  public int getUid() throws android.os.RemoteException;
  public int checkPermission(java.lang.String permission) throws android.os.RemoteException;
  public moe.shizuku.server.IRemoteProcess newProcess(java.lang.String[] cmd, java.lang.String[] env, java.lang.String dir) throws android.os.RemoteException;
  public java.lang.String getSELinuxContext() throws android.os.RemoteException;
  public java.lang.String getSystemProperty(java.lang.String name, java.lang.String defaultValue) throws android.os.RemoteException;
  public void setSystemProperty(java.lang.String name, java.lang.String value) throws android.os.RemoteException;
  public int addUserService(moe.shizuku.server.IShizukuServiceConnection conn, Bundle args) throws android.os.RemoteException;
  public int removeUserService(moe.shizuku.server.IShizukuServiceConnection conn, Bundle args) throws android.os.RemoteException;
  public void requestPermission(int requestCode) throws android.os.RemoteException;
  public boolean checkSelfPermission() throws android.os.RemoteException;
  public boolean shouldShowRequestPermissionRationale() throws android.os.RemoteException;
  public void attachApplication(moe.shizuku.server.IShizukuApplication application, Bundle args) throws android.os.RemoteException;
  public void exit() throws android.os.RemoteException;
  public void attachUserService(android.os.IBinder binder, Bundle options) throws android.os.RemoteException;
  public void dispatchPackageChanged(Intent intent) throws android.os.RemoteException;
  public boolean isHidden(int uid) throws android.os.RemoteException;
  public void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, Bundle data) throws android.os.RemoteException;
  public int getFlagsForUid(int uid, int mask) throws android.os.RemoteException;
  public void updateFlagsForUid(int uid, int mask, int value) throws android.os.RemoteException;
}
