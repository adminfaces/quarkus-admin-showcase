/*
 * Copyright 2009-2014 PrimeTek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.adminfaces.showcase.bean;

import org.omnifaces.cdi.ViewScoped;

import javax.annotation.PostConstruct;
import javax.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Named
@ViewScoped
public class RadioMB implements Serializable {
    
    private String console;    
    private String city; 
    private String city2; 
    private List<String> cities;  
    private String color;
    
    @PostConstruct
    public void init() {
        cities = new ArrayList<String>();
        cities.add("Miami");
        cities.add("London");
        cities.add("Paris");
        cities.add("Istanbul");
        cities.add("Berlin");
        cities.add("Barcelona");
        cities.add("Rome");
        cities.add("Brasilia");
        cities.add("Amsterdam");
    }

    public String getConsole() {
        return console;
    }

    public void setConsole(String console) {
        this.console = console;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCity2() {
        return city2;
    }

    public void setCity2(String city2) {
        this.city2 = city2;
    }
    
    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public List<String> getCities() {
        return cities;
    }
}
