/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package moe.shizuku.server;
public interface IShizukuServiceConnection extends android.os.IInterface
{
  /** Default implementation for IShizukuServiceConnection. */
  public static class Default implements moe.shizuku.server.IShizukuServiceConnection
  {
    @Override public void connected(android.os.IBinder service) throws android.os.RemoteException
    {
    }
    @Override public void died() throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements moe.shizuku.server.IShizukuServiceConnection
  {
    private static final java.lang.String DESCRIPTOR = "moe.shizuku.server.IShizukuServiceConnection";
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an moe.shizuku.server.IShizukuServiceConnection interface,
     * generating a proxy if needed.
     */
    public static moe.shizuku.server.IShizukuServiceConnection asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof moe.shizuku.server.IShizukuServiceConnection))) {
        return ((moe.shizuku.server.IShizukuServiceConnection)iin);
      }
      return new moe.shizuku.server.IShizukuServiceConnection.Stub.Proxy(obj);
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
        case TRANSACTION_connected:
        {
          data.enforceInterface(descriptor);
          android.os.IBinder _arg0;
          _arg0 = data.readStrongBinder();
          this.connected(_arg0);
          return true;
        }
        case TRANSACTION_died:
        {
          data.enforceInterface(descriptor);
          this.died();
          return true;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
    }
    private static class Proxy implements moe.shizuku.server.IShizukuServiceConnection
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
      @Override public void connected(android.os.IBinder service) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeStrongBinder(service);
          boolean _status = mRemote.transact(Stub.TRANSACTION_connected, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().connected(service);
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      @Override public void died() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_died, _data, null, android.os.IBinder.FLAG_ONEWAY);
          if (!_status && getDefaultImpl() != null) {
            getDefaultImpl().died();
            return;
          }
        }
        finally {
          _data.recycle();
        }
      }
      public static moe.shizuku.server.IShizukuServiceConnection sDefaultImpl;
    }
    static final int TRANSACTION_connected = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_died = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    public static boolean setDefaultImpl(moe.shizuku.server.IShizukuServiceConnection impl) {
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
    public static moe.shizuku.server.IShizukuServiceConnection getDefaultImpl() {
      return Stub.Proxy.sDefaultImpl;
    }
  }
  public void connected(android.os.IBinder service) throws android.os.RemoteException;
  public void died() throws android.os.RemoteException;
}
