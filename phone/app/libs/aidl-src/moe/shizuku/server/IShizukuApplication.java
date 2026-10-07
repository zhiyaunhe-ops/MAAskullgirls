/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package moe.shizuku.server;
public interface IShizukuApplication extends android.os.IInterface
{
  /** Default implementation for IShizukuApplication. */
  public static class Default implements moe.shizuku.server.IShizukuApplication
  {
    @Override public void bindApplication(Bundle data) throws android.os.RemoteException
    {
    }
    @Override public void dispatchRequestPermissionResult(int requestCode, Bundle data) throws android.os.RemoteException
    {
    }
    // Sui only

    @Override public void showPermissionConfirmation(int requestUid, int requestPid, java.lang.String requestPackageName, int requestCode) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements moe.shizuku.server.IShizukuApplication
  {
    private static final java.lang.String DESCRIPTOR = "moe.shizuku.server.IShizukuApplication";
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an moe.shizuku.server.IShizukuApplication interface,
     * generating a proxy if needed.
     */
    public static moe.shizuku.server.IShizukuApplication asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof moe.shizuku.server.IShizukuApplication))) {
        return ((moe.shizuku.server.IShizukuApplication)iin);
      }
      return new moe.shizuku.server.IShizukuApplication.Stub.Proxy(obj);
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
        case TRANSACTION_bindApplication:
        {
          data.enforceInterface(descriptor);
          Bundle _arg0;
          if ((0!=data.readInt())) {
            _arg0 = Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg0 = null;
          }
          this.bindApplication(_arg0);
          return true;
        }
        case TRANSACTION_dispatchRequestPermissionResult:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          Bundle _arg1;
          if ((0!=data.readInt())) {
            _arg1 = Bundle.CREATOR.createFromParcel(data);
          }
          else {
            _arg1 = null;
          }
          this.dispatchRequestPermissionResult(_arg0, _arg1);
          return true;
        }
        case TRANSACTION_showPermissionConfirmation:
        {
          data.enforceInterface(descriptor);
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          java.lang.String _arg2;
          _arg2 = data.readString();
          int _arg3;
          _arg3 = data.readInt();
          this.showPermissionConfirmation(_arg0, _arg1, _arg2, _arg3);
          reply.writeNoException();
          return true;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
    }
    private static class Proxy implements moe.shizuku.server.IShizukuApplication
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
      @Override public void bindApplication(Bundle data) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          if ((data!=null)) {
            _data.writeInt(1);
            data.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_bindApplication, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().bindApplication(data);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void dispatchRequestPermissionResult(int requestCode, Bundle data) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(requestCode);
          if ((data!=null)) {
            _data.writeInt(1);
            data.writeToParcel(_data, 0);
          }
          else {
            _data.writeInt(0);
          }
          boolean _status = mRemote.transact(Stub.TRANSACTION_dispatchRequestPermissionResult, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().dispatchRequestPermissionResult(requestCode, data);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      // Sui only

      @Override public void showPermissionConfirmation(int requestUid, int requestPid, java.lang.String requestPackageName, int requestCode) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(requestUid);
          _data.writeInt(requestPid);
          _data.writeString(requestPackageName);
          _data.writeInt(requestCode);
          boolean _status = mRemote.transact(Stub.TRANSACTION_showPermissionConfirmation, _data, _reply, 0);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().showPermissionConfirmation(requestUid, requestPid, requestPackageName, requestCode);
            return;
          }
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      public static moe.shizuku.server.IShizukuApplication sDefaultImpl;
    }
    static final int TRANSACTION_bindApplication = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_dispatchRequestPermissionResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_showPermissionConfirmation = (android.os.IBinder.FIRST_CALL_TRANSACTION + 10000);
    public static boolean setDefaultImpl(moe.shizuku.server.IShizukuApplication impl) {
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
    public static moe.shizuku.server.IShizukuApplication getDefaultImpl() {
      return Stub.Proxy.sDefaultImpl;
    }
  }
  public void bindApplication(Bundle data) throws android.os.RemoteException;
  public void dispatchRequestPermissionResult(int requestCode, Bundle data) throws android.os.RemoteException;
  // Sui only

  public void showPermissionConfirmation(int requestUid, int requestPid, java.lang.String requestPackageName, int requestCode) throws android.os.RemoteException;
}
