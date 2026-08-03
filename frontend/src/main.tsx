import React from 'react';
import ReactDOM from 'react-dom/client';
import { Provider } from 'react-redux';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { store } from './store';
import { loggedOut } from './store/authSlice';
import { setUnauthorizedHandler } from './api/client';
import './styles.css';

// Bei HTTP 401 automatisch abmelden – RequireAuth leitet dann zur Login-Seite um.
setUnauthorizedHandler(() => {
  store.dispatch(loggedOut());
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Provider store={store}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </Provider>
  </React.StrictMode>,
);
