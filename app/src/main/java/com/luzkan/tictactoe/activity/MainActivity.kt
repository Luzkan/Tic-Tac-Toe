package com.luzkan.tictactoe.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.IdpResponse
import com.luzkan.tictactoe.database.models.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.android.synthetic.main.activity_main.*
import java.util.ArrayList
import android.widget.Toast
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.iid.FirebaseInstanceId
import com.luzkan.tictactoe.adapter.OnlineAdapter
import com.luzkan.tictactoe.R
import com.luzkan.tictactoe.interfaces.Util.getCurrentUsername

class MainActivity : AppCompatActivity() {

    private val users = ArrayList<User>()
    private var adapter: OnlineAdapter? = null
    private var loggedIn = false
    private val handler = Handler()
    private val FB_REQUEST_CODE: Int = 997

    private lateinit var dbs: FirebaseFirestore
    private lateinit var db: FirebaseDatabase

    // Watch for logged in users and put them into User List
    private fun fetchUsers(currentUser: FirebaseUser) {
        FirebaseDatabase.getInstance().reference.child("Users").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {

                // Clear on change detected and re-add (if it's not the current user itself)
                users.clear()
                for (snapshot in dataSnapshot.children)
                    if(snapshot.hasChild("connections"))
                        if (snapshot.key != currentUser.displayName) users.add(snapshot.child("info").getValue(User::class.java)!!)

                // Notify adapter
                adapter!!.notifyDataSetChanged()
            }
            override fun onCancelled(databaseError: DatabaseError) {}
        })
    }

    // Check if a player is currently waiting for current user in a game
    private fun checkIfPlays(lookForLobby: String, createLobby: String) {
        FirebaseDatabase.getInstance().reference.child("Games").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {

                // Looking for lobby - if now found - it will proceed to create a new lobby
                if(dataSnapshot.child(lookForLobby).child("connected").hasChildren()){
                    // Found lobby, detecting if player was first (on resume case) or not
                    Toast.makeText(applicationContext, "Joining game: $lookForLobby",Toast.LENGTH_SHORT).show()
                    val first = dataSnapshot.child(createLobby).child("first").getValue(String::class.java)
                    if (first == getCurrentUsername()) playMultiplayer(true, lookForLobby, false)
                    else                               playMultiplayer(false, lookForLobby, false)
                }else{
                    // Check if there was a game in progress before
                    val resumingGame = dataSnapshot.child(createLobby).child("map").hasChildren()
                    if (resumingGame) Toast.makeText(applicationContext, "Resuming game: $createLobby", Toast.LENGTH_SHORT).show()
                    else              Toast.makeText(applicationContext, "Starting game: $createLobby", Toast.LENGTH_SHORT).show()

                    // Detecting if player was first (on resume case) or not else creating new lobby
                    if (dataSnapshot.child(createLobby).child("first").hasChildren()){
                        val first = dataSnapshot.child(createLobby).child("first").getValue(String::class.java)
                        if (first == getCurrentUsername()) playMultiplayer(true, createLobby, false)
                        else                               playMultiplayer(false, createLobby, false)
                    }else{
                        playMultiplayer(true, createLobby, true)
                    }
                }
            }
            override fun onCancelled(databaseError: DatabaseError) {}
        })
    }

    // Animation after Splash Screen
    private val runnableSplash = {
        TransitionManager.beginDelayedTransition(lRoot)
        lRegister.visibility = View.VISIBLE
    }

    // Reverting loading button
    private val runnableButton = {
        bSubmit.stopAnimation()
        bSubmit.revertAnimation()
        bSubmit.background = getDrawable(R.drawable.button_round2)
    }

    // Setting LoggedIn and Online Features
    @SuppressLint("PrivateResource")
    private val runnableStartApp = {
        loggedIn = true
        onlineMode()
        setLobby("main")
    }

    // List of account types we can sign in
    private val providers = arrayListOf(
        AuthUI.IdpConfig.FacebookBuilder().build()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Create Firebase stuff
        dbs = FirebaseFirestore.getInstance()
        db = FirebaseDatabase.getInstance()
        
        // Login Button & Dynamic enabling
        bSubmit.background = getDrawable(R.drawable.button_round2)
        checkBlank(true)

        // Log out
        if (intent.getBooleanExtra("logout", false)) {
            setLobby("login")
        }

        // Facebook login fix
        facebook_login.setReadPermissions("email")

        // Splash Screen & Load User List
        handler.postDelayed(runnableSplash, 1500)
        if (FirebaseAuth.getInstance().currentUser != null) {

            // Notifications PushID getter
            FirebaseInstanceId.getInstance().instanceId.addOnCompleteListener(OnCompleteListener { task ->
                if (!task.isSuccessful) return@OnCompleteListener
                // Token for Notification Push
                Log.d("Token", task.result?.token)
            })

            Log.d("USER","${FirebaseAuth.getInstance().currentUser?.displayName} ${FirebaseAuth.getInstance().currentUser?.email}")
            runnableStartApp()
        }

        // Buttons click actions in main lobby
        playAI.setOnClickListener{
            val intent = Intent(this, PlayActivity::class.java)
            intent.putExtra("aiMode", true)
            intent.putExtra("gameName", "")
            startActivity(intent)
        }

        playVS.setOnClickListener{
            val intent = Intent(this, PlayActivity::class.java)
            intent.putExtra("aiMode", false)
            intent.putExtra("gameName", "")
            startActivity(intent)
        }

        backToMain.setOnClickListener{
            setLobby("main")
        }

        settings.setOnClickListener{
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Set up visibility of everything to look like main menu on start
        setLobby("main")
    }

    // Set up online database features
    private fun onlineMode() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        val myConnectionsRef = db.getReference("/Users/" + currentUser!!.displayName + "/connections")
        val pushMyInfo = db.getReference("/Users/" + currentUser.displayName + "/info")
        val lastOnlineRef = db.getReference("/Users/" + currentUser.displayName + "/lastOnline")
        val connectedRef = db.getReference(".info/connected")

        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    val con = myConnectionsRef.push()
                    pushMyInfo.setValue(currentUser)
                    // When this device disconnects, remove it
                    con.onDisconnect().removeValue()
                    con.setValue(java.lang.Boolean.TRUE)
                    lastOnlineRef.onDisconnect().setValue(ServerValue.TIMESTAMP)
                }
            }
            override fun onCancelled(error: DatabaseError) { }
        })

        // List of logged users
        fetchUsers(currentUser)
        adapter = OnlineAdapter(users)
        listOnline.adapter = adapter
        listOnline.layoutManager = LinearLayoutManager(this)
    }

    // Invite button creates two options for gameLobby names and checks
    fun inviteClick(view: View) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        val lobbyExist = view.tooltipText.toString() + "-" + currentUser!!.displayName
        val createLobby = currentUser.displayName + "-" + view.tooltipText.toString()
        checkIfPlays(lobbyExist, createLobby)
    }

    private fun playMultiplayer(first: Boolean, lobbyName: String, newFirst: Boolean) {
        // Intents to set up multiplayer game
        val intent = Intent(this, PlayActivity::class.java)
        intent.putExtra("online", true)
        intent.putExtra("gameName", lobbyName)

        // Handle who goes first depending on the existence (or its absence) of the game
        if (first) intent.putExtra("first", true)
        else       intent.putExtra("first", false)

        if (newFirst){
            val pushGameInfo = db.getReference("/Games/$lobbyName/first")
            pushGameInfo.setValue(getCurrentUsername())
        }

        // Prep database to handle the game
        val pushGameInfo = db.getReference("/Games/$lobbyName/gameInProgress")
        pushGameInfo.onDisconnect().setValue(java.lang.Boolean.FALSE)
        pushGameInfo.setValue(java.lang.Boolean.TRUE)

        val myConnectionsRef = db.getReference("/Games/$lobbyName/connected")
        val con = myConnectionsRef.push()
        con.onDisconnect().removeValue()
        con.setValue(java.lang.Boolean.TRUE)

        startActivity(intent)
    }

    // Allows us to open activity to sign in with facebook
    fun showSignOptions(view: View) {
        startActivityForResult(
            AuthUI.getInstance().createSignInIntentBuilder().setAvailableProviders(providers).setTheme(
                R.style.AppTheme
            ).build(), FB_REQUEST_CODE
        )
    }

    // Logs user with facebook
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FB_REQUEST_CODE) {
            // Response:
            IdpResponse.fromResultIntent(data)
            if (resultCode == Activity.RESULT_OK) {
                // User:
                FirebaseAuth.getInstance().currentUser
                startApp()
            }
        }
    }

    fun bSubmitClick(view: View) {
        // Animated loading button
        bSubmit.startAnimation()
        resetError()

        val email = etEmail.text.toString()
        val password = etPassword.text.toString()

        if (logoImage.tag == "signup") {
            val userName = etUsername.text.toString()
            val passwordCheck = etPasswordConfirm.text.toString()

            if (passwordCheck == password) {
                userName.toLowerCase()
                createUser(email, password, userName)
            } else {
                setError("Those passwords didn't match.")
            }

        } else {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).addOnSuccessListener {
                startApp()
            }.addOnFailureListener {
                setError("Invalid login or password.")
            }
        }
    }

    private fun setError(error: String) {
        tvError.visibility = View.VISIBLE
        tvError.text = error
        setSubmitButton(R.drawable.cross)
    }

    private fun resetError() {
        tvError.text = ""
        tvError.visibility = View.GONE
    }

    // Dynamic all editText check
    private fun checkBlank(register: Boolean) {
        if (register) {
            checkIfEmpty(etEmail, true)
            checkIfEmpty(etPassword, true)
            checkIfEmpty(etPasswordConfirm, true)
            checkIfEmpty(etUsername, true)
        } else {
            checkIfEmpty(etEmail, false)
            checkIfEmpty(etPassword, false)
        }
    }

    // Dynamic single editText check
    private fun checkIfEmpty(editable: EditText, register: Boolean) {
        editable.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                editable.tag = p0!!.isNotEmpty()
                if (register) bSubmit.isEnabled = etEmail.tag.toString() == "true" && etPassword.tag.toString() == "true" && etUsername.tag.toString() == "true" && etPasswordConfirm.tag.toString() == "true"
                else          bSubmit.isEnabled = etEmail.tag.toString() == "true" && etPassword.tag.toString() == "true"
            }
            override fun afterTextChanged(p0: Editable?) {}
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })
    }

    // Set image after loading on button
    private fun setSubmitButton(image: Int) {
        bSubmit.doneLoadingAnimation(Color.parseColor("#FAB162"), BitmapFactory.decodeResource(resources, image))
        handler.postDelayed(runnableButton, 800)
    }

    @SuppressLint("SetTextI18n")
    private fun createUser(email: String, password: String, userName: String) {
        // Check if username is taken
        dbs.document("Users/$userName").get().addOnSuccessListener {

            // If user is null
            if (it.toObject(User::class.java) == null) {

                // Checking if email is taken
                FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val uid = FirebaseAuth.getInstance().uid ?: ""
                        val newUser = User(uid, email, userName)

                        FirebaseAuth.getInstance().currentUser!!.updateProfile(
                            UserProfileChangeRequest.Builder().setDisplayName(userName).build()
                        )

                        // Creating a new user in database
                        dbs.collection("Users").document(FirebaseAuth.getInstance().currentUser!!.uid).set(newUser).addOnSuccessListener {
                            startApp()
                        }.addOnFailureListener {
                                exception: java.lang.Exception -> setError(exception.message + ".")
                        }
                    }
                }.addOnFailureListener {
                    if (it.message?.length!! > 70) setError(it.message!!.takeLastWhile { character -> character != '[' }.take(41) + ".")
                    else                           setError(it.message.toString())
                }
            } else setError("This username is taken.")
        }
    }

    // Setting App after finished logging in/register like for a logged in user on app launch
    private fun startApp() {
        setSubmitButton(R.drawable.tick)
        handler.postDelayed(runnableStartApp, 500)
    }

    // Changing mode login/sign up on textView click
    fun tvChangeClick(view: View) {
        resetError()
        when {
            logoImage.tag == "main" -> setLobby("main")
            logoImage.tag == "signup" -> setLobby("login")
            logoImage.tag == "login" -> setLobby("signup")
        }
    }

    // Changing mode login/sign up on textView click
    fun playMultiClick(view: View) {
        if (!loggedIn) setLobby("login")
        else           setLobby("lobby")
    }

    private fun setLobby(panel: String){
        resetError()
        TransitionManager.beginDelayedTransition(lRoot)

        etEmail.visibility = View.GONE
        etUsername.visibility = View.GONE
        etPassword.visibility = View.GONE
        etPasswordConfirm.visibility = View.GONE
        facebook_login.visibility = View.GONE
        tvChange.visibility = View.GONE
        bSubmit.visibility = View.GONE
        settings.visibility = View.GONE

        playAI.visibility = View.GONE
        playMulti.visibility = View.GONE
        playVS.visibility = View.GONE
        listOnline.visibility = View.GONE
        backToMain.visibility = View.GONE

        if(panel == "main"){
            logoImage.tag = "main"
            playAI.visibility = View.VISIBLE
            playMulti.visibility = View.VISIBLE
            playVS.visibility = View.VISIBLE
            if (loggedIn) settings.visibility = View.VISIBLE

        }else if(panel == "lobby"){
            logoImage.tag = "lobby"
            listOnline.visibility = View.VISIBLE
            backToMain.visibility = View.VISIBLE

        }else if(panel == "login"){
            logoImage.tag = "login"
            etEmail.visibility = View.VISIBLE
            etPassword.visibility = View.VISIBLE
            facebook_login.visibility = View.VISIBLE
            tvChange.visibility = View.VISIBLE
            tvChange.text = getString(R.string.signupalter)
            bSubmit.visibility = View.VISIBLE
            bSubmit.text = getString(R.string.login)
            backToMain.visibility = View.VISIBLE

        }else if(panel == "signup"){
            logoImage.tag = "signup"
            etEmail.visibility = View.VISIBLE
            etPassword.visibility = View.VISIBLE
            facebook_login.visibility = View.VISIBLE
            tvChange.visibility = View.VISIBLE
            tvChange.text = getString(R.string.loginalter)
            bSubmit.visibility = View.VISIBLE
            bSubmit.text = getString(R.string.signup)
            backToMain.visibility = View.VISIBLE
            etUsername.visibility = View.VISIBLE
            etPasswordConfirm.visibility = View.VISIBLE
            backToMain.visibility = View.VISIBLE
        }

        checkBlank(false)
    }

    // Hiding keyboard when click outside the EditText
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (currentFocus != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(currentFocus!!.windowToken, 0)
        }
        return super.dispatchTouchEvent(ev)
    }
}