package edu.buffalo.cse.cse486586.simpledynamo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.List;
import java.util.PriorityQueue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

public class SimpleDynamoProvider extends ContentProvider {

    final static String TAG = ContentProvider.class.getSimpleName();

    static final int SERVER_PORT = 10000;
    static final List<String> PORTS = Arrays.asList("11108", "11112", "11116", "11120", "11124");
    final static String KEY = "key";
    final static String VALUE = "value";
    private static String[] matrixColumns = new String[]{KEY, VALUE};
    private String myPort;
    private static final String RECEIVED = "received";
    private Node root;
    Uri mUri;
    private static final int TIMEOUT = 1000;
    boolean isRecovered = false;

    private class Node {
        String portNumber;
        String nodeId;
        Node successor=null;
        Node predecessor=null;

        @Override
        public boolean equals(Object o) {
            return (o instanceof Node) && nodeId.equalsIgnoreCase(((Node)o).nodeId);
        }
    }

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		// TODO Auto-generated method stub

        performDelete(selection, null, myPort);
		return 0;
	}

    public void performDelete(String selection, String nodeId, String initiatorPort) {
        if(!isRecovered) {
            recover();
        }
        try {
            if ("*".equalsIgnoreCase(selection)) {
                globalDelete(selection, null, initiatorPort);
            } else if ("@".equalsIgnoreCase(selection)) {
                localDelete(nodeId);
            } else {
                singleDelete(selection, nodeId, initiatorPort);
            }
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    private void globalDelete(String selection, String nodeId, String initiatorPort) {
        localDelete(nodeId);
        if(initiatorPort.equalsIgnoreCase(root.portNumber)) {
            Node current = root.successor;
            while (current.successor != root) {
                sendDeleteRequest(selection, current, current.nodeId, initiatorPort);
                current = current.successor;
            }
        }
    }

    private void localDelete(String nodeId) {
        String[] files = getContext().fileList();
        for(String file: files) {
            try {
                deleteLocally(file, nodeId);
                Log.d("localQuery()", file);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Log.v("delete", "@");
    }

    private void deleteLocally(String selection, String nodeId) {
        boolean delete = false;
        try {
            FileInputStream fileInputStream = getContext().openFileInput(selection);
            BufferedReader inputReader = new BufferedReader(new InputStreamReader(fileInputStream));
            String line = inputReader.readLine();
            String[] v = line.split("\\|");
            if(nodeId == null || v[2].equalsIgnoreCase(nodeId)) {
                delete = true;
            }
            fileInputStream.close();
            if(delete) {
                getContext().deleteFile(selection);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d("delete","Local: selection: "+selection);
    }

    private void singleDelete(String selection, String nodeId, String initiatorPort) throws NoSuchAlgorithmException {
        if(nodeId != null) {
            deleteLocally(selection, nodeId);
            return;
        }
        Node node = getRelevantNode(selection);
        if(node == null) {
            Log.d("delete","relevant node not found: "+selection);
            return;
        }
        String owner = node.nodeId;
        for(int i = 0; i < 3; i++) {
            sendDeleteRequest(selection, node, owner, initiatorPort);
            node = node.successor;
        }
//        if(root.successor == null) {
//            deleteLocally(selection);
//        } else {
//            String currHash = genHash(root.nodeId);
//       w3     String predHash = genHash(root.predecessor.nodeId);
//            String selectionHash = genHash(selection);
//            if (currHash.compareTo(selectionHash) > 0
//                    && predHash.compareTo(selectionHash) < 0) {
//                deleteLocally(selection);
//            } else if (predHash.compareTo(currHash) > 0
//                    && (selectionHash.compareTo(currHash) < 0
//                    || selectionHash.compareTo(predHash) > 0)) {
//                deleteLocally(selection);
//            } else {
//                sendDeleteRequest(selection, initiatorPort);
//            }
//        }
    }

    private void sendDeleteRequest(String selection,Node node,String owner, String initiatorPort) {
        String msgToSend = "delete:"+myPort+":"+selection+":"+owner+":"+initiatorPort;
        Socket socket;
        String message;
        try {
            socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                    Integer.parseInt(node.portNumber));
//            socket.setSoTimeout(TIMEOUT);
            BufferedReader cIncoming = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream cOutputStream = socket.getOutputStream();
            PrintWriter cWriter = new PrintWriter(cOutputStream, true);
            cWriter.println(msgToSend);

            while ((message = cIncoming.readLine()) != null) {
                Log.d("delete socket", message);
                if (message.contains(RECEIVED)) {
                    break;
                }
            }
            socket.close();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        Log.d("delete","Remote: selection: "+selection);
    }

	@Override
	public String getType(Uri uri) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		// TODO Auto-generated method stub
        String key = values.getAsString(KEY);
        String value = values.getAsString(VALUE);
        performInsert(key, value, null);
        return uri;
	}

    synchronized private void performInsert(String key, String value, String owner) {
        if(!isRecovered) {
            recover();
        }
        if(owner != null) {
            insertLocally(key, value, owner);
            return;
        }
        insertInRelevantNode(key,value);
    }

    private void insertLocally(String key, String value) {
        insertLocally(key,value, root.nodeId);
    }

    private void insertLocally(String key, String value, String owner) {
        Context c = getContext();
        FileOutputStream outputStream;
        try {
            assert c != null;

            outputStream = c.openFileOutput(key, Context.MODE_PRIVATE);
            PrintWriter writer = new PrintWriter(outputStream, true);
            String wString = key+"|"+value+"|"+owner;
            writer.write(wString);
            Log.v("insert", wString);
            writer.close();
            outputStream.close();

        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "File write failed: " + key + "-"+value);
        }
    }

    private void sendInsertRequest(final String key, final String value, final Node node) {
        final String msg = "insert:"+myPort+":"+key+":"+value+":"+node.nodeId;
        sendMessage(msg,node.portNumber);
    }

    private void sendReplicationRequest(final String key, final String value, String owner, final Node node) {
        final String msg = "replicate:"+myPort+":"+key+":"+value+":"+owner;
        sendMessage(msg,node.portNumber);
    }

    private void insertInRelevantNode(String key, String value) {
        Node node = getRelevantNode(key);
        if(node == null) {
            Log.d("insert","relevant node not found: "+key);
            return;
        }
        String owner = node.nodeId;
        for(int i = 0; i < 3; i++) {
            if(i == 0) {
                sendInsertRequest(key, value, node);
            } else {
                sendReplicationRequest(key, value, owner, node);
            }
            node = node.successor;
        }
    }


    RecoveryTask task;
	@Override
	public boolean onCreate() {
		// TODO Auto-generated method stub
        TelephonyManager tel = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        Log.d(TAG, portStr);
        Log.d(TAG, tel.getLine1Number());
        mUri = buildUri("content", "edu.buffalo.cse.cse486586.simpledht.provider");
        myPort = String.valueOf((Integer.parseInt(portStr) * 2));

        try {
            ServerSocket socket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,socket);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        root = new Node();
        root.nodeId = portStr;
        root.portNumber = myPort;

        setupRing(PORTS);

        task = new RecoveryTask();
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        return true;
	}

    private Uri buildUri(String scheme, String authority) {
        Uri.Builder uriBuilder = new Uri.Builder();
        uriBuilder.authority(authority);
        uriBuilder.scheme(scheme);
        return uriBuilder.build();
    }

	@Override
	public Cursor query(Uri uri, String[] projection, String selection,
			String[] selectionArgs, String sortOrder) {
		// TODO Auto-generated method stub
        MatrixCursor matrixCursor = new MatrixCursor(matrixColumns);
        String values = performQuery(selection, null, myPort);
        if(!values.isEmpty()) {

            String[] keyVals = values.split(";");

            for (String keyVal : keyVals) {
                if (!keyVal.isEmpty()) {
                    String[] p = keyVal.split("\\|");
                    Object[] row = new Object[2];
                    row[0] = p[0];
                    row[1] = p[1];
                    matrixCursor.addRow(row);
                }
            }
        }
        return matrixCursor;
	}

    private String performQuery(String selection,String nodeId , String initiatorPort) {
        if(!isRecovered) {
            recover();
        }
        String values = "";
        if ("*".equalsIgnoreCase(selection)) {
            values = globalQuery(selection, nodeId, initiatorPort);
        } else if ("@".equalsIgnoreCase(selection)) {
            values = localQuery(nodeId);
        } else {
            values = singleQuery(selection, nodeId, initiatorPort);
        }
        return values;
    }

    private String globalQuery(String selection,String nodeId, String initiatorPort) {
        StringBuilder values= new StringBuilder();
        values.append(localQuery(nodeId));
        if(initiatorPort.equalsIgnoreCase(root.portNumber)) {
            Node current = root.successor;
            while (current.successor != root) {
                values.append(";").append(sendQueryRequest(selection, current, initiatorPort));
                current = current.successor;
            }
        }
        return values.toString();
    }

    private String localQuery(String nodeId) {
//        if(nodeId == null) {
//            nodeId = root.nodeId;
//        }
        String[] files = getContext().fileList();
        List<String> values = new ArrayList<String>();
        for(String file: files) {
            try {
                String result = queryLocally(file, nodeId);
                if(result != null && !result.isEmpty()) {
                    values.add(result);
                    Log.d("localQuery()", result);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Log.v("query", "@");
        return TextUtils.join(";",values);
    }

    private String singleQuery(String selection, String nodeId, String initiatorPort) {
        String value = "";
        if(nodeId != null) {
            value = queryLocally(selection, nodeId);
            return value;
        }
        Node node = getRelevantNode(selection);
        if (node != null) {
            if (root.equals(node)) {
                value = queryLocally(selection, node.nodeId);
            } else {
                value = sendQueryRequest(selection, node, initiatorPort);
            }
        }
//        if(root.successor == null) {
//            value = queryLocally(selection);
//        } else {
//            String currHash = genHash(root.nodeId);
//            String predHash = genHash(root.predecessor.nodeId);
//            String selectionHash = genHash(selection);
//            if (currHash.compareTo(selectionHash) > 0
//                    && predHash.compareTo(selectionHash) < 0) {
//                value = queryLocally(selection, root.nodeId);
//            } else if (predHash.compareTo(currHash) > 0
//                    && (selectionHash.compareTo(currHash) < 0
//                    || selectionHash.compareTo(predHash) > 0)) {
//                value = queryLocally(selection);
//            } else {
//                value = sendQueryRequest(selection, initiatorPort);
//            }
//        }
        Log.v("query", "selection: "+ selection + " | value: " + value);
        return value;
    }

    private String sendQueryRequest(String selection,Node node, String initiatorPort) {
        String msgToSend = "query:"+myPort+":"+selection+":"+node.nodeId+":"+initiatorPort;
        Socket socket = null;
        String message = "";
        for (int i=0; i < 3; i++) {
            try {
                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                        Integer.parseInt(node.portNumber));
//                socket.setSoTimeout(TIMEOUT);
                Log.d("query","msgToSend: " + msgToSend + "-> to port: "+node.portNumber);
                BufferedReader cIncoming = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                OutputStream cOutputStream = socket.getOutputStream();
                PrintWriter cWriter = new PrintWriter(cOutputStream, true);
                cWriter.println(msgToSend);

                while ((message = cIncoming.readLine()) != null) {
                    Log.d("query socket", message);
                    if (message.contains("query_result")) {
                        break;
                    }
                }
                socket.close();
                if(message != null) {
                    break;
                } else {
                    node = node.successor;
                    Log.d("query", "message was null");
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
                node = node.successor;
                Log.d("query", "Query next Node: "+node.nodeId);
                if(socket != null) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        Log.d("query","Remote: selection: "+selection+" | value: "+ message);
        return processMessage(message);
    }

    private String processMessage(String message) {
        if(message == null || message.isEmpty()){
            return "";
        }
        String[] parts = message.split(":");
        if(parts.length<3) {
            Log.e("Error","Message should contain 2 parts");
            return "";
        }
        return parts[2];
    }

    private String queryLocally(String selection, String nodeId) {
        String value = "";
        try {
            FileInputStream fileInputStream = getContext().openFileInput(selection);
            BufferedReader inputReader = new BufferedReader(new InputStreamReader(fileInputStream));
            String line = inputReader.readLine();
            String[] v = line.split("\\|");
            if(nodeId == null || v[2].equalsIgnoreCase(nodeId)) {
                value = v[0] + "|" + v[1];
            }
            fileInputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.d("query","Local: selection: "+selection+" | value: "+ value);
        return value;
    }

	@Override
	public int update(Uri uri, ContentValues values, String selection,
			String[] selectionArgs) {
		// TODO Auto-generated method stub
		return 0;
	}

    private String genHash(String input) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sha1Hash = sha1.digest(input.getBytes());
        Formatter formatter = new Formatter();
        for (byte b : sha1Hash) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }


    synchronized private void recover() {
	    if(isRecovered) {
	        return;
        }
        localDelete(null);
        recoverOwnData();
        recoverReplicationData();
        isRecovered = true;
    }

    private void recoverOwnData() {
        String values = sendRecoveryRequest();
        String[] keyvalue = values.split(";");
        for (String kv : keyvalue) {
            if(kv.isEmpty()) {
                continue;
            }
            String[] v = kv.split("\\|");
            insertLocally(v[0], v[1], root.nodeId);
        }
    }

    private String sendRecoveryRequest() {
        Node node = root;
        String selection = "@";
        String msgToSend = "query:"+myPort+":"+selection+":"+node.nodeId+":"+myPort;
        Socket socket;
        String message = "";
        for (int i=0; i < 2; i++) {
            try {
                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                        Integer.parseInt(node.successor.portNumber));
                BufferedReader cIncoming = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                OutputStream cOutputStream = socket.getOutputStream();
                PrintWriter cWriter = new PrintWriter(cOutputStream, true);
                cWriter.println(msgToSend);

                while ((message = cIncoming.readLine()) != null) {
                    Log.d("query socket", message);
                    if (message.contains("query_result")) {
                        break;
                    }
                }
                socket.close();
                break;
            } catch (IOException ioe) {
                ioe.printStackTrace();
                node = node.successor;
            }
        }
        Log.d("Recovery Request","Remote: selection: "+selection+" | value: "+ message);
        return processMessage(message);
    }

    private void recoverReplicationData() {
        Node node = root;
        for(int i=0; i < 2; i++) {
            String values = sendReplicaRequest(node.predecessor);
            String[] keyvalue = values.split(";");
            for (String kv : keyvalue) {
                if(kv.isEmpty()) {
                    continue;
                }
                String[] v = kv.split("\\|");
                insertLocally(v[0], v[1], node.predecessor.nodeId);
            }
            node = node.predecessor;
        }
    }


    private String sendReplicaRequest(Node node) {
        String selection = "@";
        String msgToSend = "query:"+myPort+":"+selection+":"+node.nodeId+":"+myPort;
        Socket socket;
        String message = "";
        for (int i=0; i < 2; i++) {
            try {
                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                        Integer.parseInt(node.portNumber));
//                socket.setSoTimeout(300);
                BufferedReader cIncoming = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                OutputStream cOutputStream = socket.getOutputStream();
                PrintWriter cWriter = new PrintWriter(cOutputStream, true);
                cWriter.println(msgToSend);

                while ((message = cIncoming.readLine()) != null) {
                    Log.d("query socket", message);
                    if (message.contains("query_result")) {
                        break;
                    }
                }
                socket.close();
                break;
            } catch (IOException ioe) {
                ioe.printStackTrace();
                if(node.equals(root.predecessor.predecessor)) {
                    node = node.successor;
                } else {
                    node = node.successor.successor;
                }
            }
        }
        Log.d("Replica Request","Remote: selection: "+selection+" | value: "+ message);
        return processMessage(message);
    }


    private Node getNode(String nodeId) {
        Node node = new Node();
        node.nodeId = nodeId;
        int portNum = Integer.parseInt(nodeId);
        node.portNumber = String.valueOf(portNum*2);
        return node;
    }

    // IMPORTANT: Not sure if it is necessary!!!
    private Node getRelevantNode(String key) {
        Node current = root;
        boolean firstVisit = true;
        while(!current.equals(root) || firstVisit) {
            firstVisit = false;
            try {
                String currHash = genHash(current.nodeId);
                String predHash = genHash(current.predecessor.nodeId);
                String keyHash = genHash(key);

                if (currHash.compareTo(keyHash) > 0
                        && predHash.compareTo(keyHash) < 0) {
                    return current;
                } else if (predHash.compareTo(currHash) > 0
                        && (keyHash.compareTo(currHash) < 0
                        || keyHash.compareTo(predHash) > 0)) {
                    return current;
                } else {
                    current = current.successor;
                }
            } catch(NoSuchAlgorithmException nsae) {
                nsae.printStackTrace();
            }
        }
        return null;
    }

    private void setupRing(List<String> ports) {
        for(String port: ports) {
            String nodeId = String.valueOf(Integer.parseInt(port) / 2);
            Node addNode = getNode(nodeId);

            if(root.equals(addNode)) {
                continue;
            }
            Node current = root;
            // if only one node in the ring.
            if (current.successor == null) {
                addNode.successor = root;
                addNode.predecessor = root;
                root.successor = addNode;
                root.predecessor = addNode;
            } else {
                try {
                    boolean firstVisit = true;
                    while (!current.equals(root) || firstVisit) {
                        firstVisit = false;
                        String currHash = genHash(current.nodeId);
                        String nodeHash = genHash(addNode.nodeId);
                        String predHash = genHash(current.predecessor.nodeId);
                        if (currHash.compareTo(nodeHash) >= 0
                                && predHash.compareTo(nodeHash) < 0) {
                            addNode.predecessor = current.predecessor;
                            addNode.successor = current;
                            current.predecessor.successor = addNode;
                            current.predecessor = addNode;
                            List<Node> nodes = Arrays.asList(current, current.predecessor, current.predecessor.predecessor);
                            break;
                        } else if (predHash.compareTo(currHash) > 0
                                && (nodeHash.compareTo(currHash) < 0
                                || nodeHash.compareTo(predHash) > 0)) {
                            addNode.predecessor = current.predecessor;
                            addNode.successor = current;
                            current.predecessor.successor = addNode;
                            current.predecessor = addNode;
                            List<Node> nodes = Arrays.asList(current, current.predecessor, current.predecessor.predecessor);
                            break;
                        }
                        current = current.successor;
                    }
                } catch (NoSuchAlgorithmException ne) {
                    ne.printStackTrace();
                    return;
                }
            }
            Log.d("Add Node","Node: "+nodeId);
        }
    }


    private void handleInsert(String message) {
        String[] parts = message.split(":");
        String key = parts[2];
        String value = parts[3];
        String owner = parts[4];
        ContentValues c = new ContentValues();
        c.put(KEY,key);
        c.put(VALUE,value);
        performInsert(key,value,owner);
    }


    private String handleQuery(String message) {
        String[] parts = message.split(":");
        String selection = parts[2];
        String nodeId = parts[3];
        String initiatorPort = parts[4];
        String values = performQuery(selection, nodeId, initiatorPort);
        return "query_result:"+myPort+":"+values;
    }


    private void handleDelete(String message) {
        String[] parts = message.split(":");
        String selection = parts[2];
        String nodeId = parts[3];
        String initiatorPort = parts[4];
        performDelete(selection, nodeId, initiatorPort);
    }

    private void handleReplication(String message) {
        handleInsert(message);
    }


    private String handleReply(String message) {
        String[] parts = message.split(":");

        if ("insert".equalsIgnoreCase(parts[0])) {
            handleInsert(message);
        } else if ("query".equalsIgnoreCase(parts[0])) {
            return handleQuery(message);
        } else if ("delete".equalsIgnoreCase(parts[0])) {
            handleDelete(message);
        } else if ("replicate".equalsIgnoreCase(parts[0])) {
            handleReplication(message);
        }
        return RECEIVED;
    }


    private void sendMessage(String msg,String port) {
        Log.d("Send Message",msg+":"+port);
        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR,msg,port);
    }

    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {

        @Override
        protected Void doInBackground(ServerSocket... serverSockets) {
            ServerSocket socket = serverSockets[0];

            while(true) {
                try {
                    Socket sc = socket.accept();

                    BufferedReader sIncoming = new BufferedReader(new InputStreamReader(sc.getInputStream()));
                    OutputStream sOutgoing = sc.getOutputStream();
                    PrintWriter sOutWriter = new PrintWriter(sOutgoing, true);
                    String message = "";

                    if ((message = sIncoming.readLine()) != null) {
                        Log.d("ServerTask","msg: "+ message);
                        String response = handleReply(message);
                        sOutWriter.println(response);
                    }
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);

        }
    }

    private class ClientTask extends AsyncTask<String, Void, Void> {

        @Override
        protected Void doInBackground(String... strings) {
            String msgToSend = strings[0];
            String port = strings[1];
            Socket socket;
            try {
                socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                        Integer.parseInt(port));
                socket.setSoTimeout(1000);
                Log.d("ClientTask","msg: " + msgToSend);

                BufferedReader cIncoming = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                OutputStream cOutputStream = socket.getOutputStream();
                PrintWriter cWriter = new PrintWriter(cOutputStream, true);
                cWriter.println(msgToSend);
                String message = "";

                while((message = cIncoming.readLine()) != null) {
                    if(RECEIVED.equalsIgnoreCase(message)) {
                        break;
                    }
                }
                socket.close();
            } catch (IOException e) {
                Log.d("ClientTask","Failed to connect");
//                handleFailure(msgToSend);
                e.printStackTrace();
            }
            return null;
        }
    }

    private class RecoveryTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            recover();
            return null;
        }
    }
}
