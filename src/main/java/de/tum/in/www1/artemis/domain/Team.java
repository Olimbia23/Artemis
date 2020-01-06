package de.tum.in.www1.artemis.domain;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import javax.persistence.*;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A Team of students.
 */
@Entity
@Table(name = "team")
@Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Team implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name")
    private String name;

    @Column(name = "image")
    private String image;

    @ManyToMany
    @Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
    @JoinTable(name = "team_students", joinColumns = @JoinColumn(name = "team_id", referencedColumnName = "id"), inverseJoinColumns = @JoinColumn(name = "students_id", referencedColumnName = "id"))
    private Set<User> students = new HashSet<>();

    // jhipster-needle-entity-add-field - JHipster will add fields here, do not remove
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public Team name(String name) {
        this.name = name;
        return this;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImage() {
        return image;
    }

    public Team image(String image) {
        this.image = image;
        return this;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Set<User> getStudents() {
        return students;
    }

    public Team students(Set<User> users) {
        this.students = users;
        return this;
    }

    public Team addStudents(User user) {
        this.students.add(user);
        return this;
    }

    public Team removeStudents(User user) {
        this.students.remove(user);
        return this;
    }

    public void setStudents(Set<User> users) {
        this.students = users;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Team team = (Team) o;
        if (team.getId() == null || getId() == null) {
            return false;
        }
        return Objects.equals(getId(), team.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }

    @Override
    public String toString() {
        return "Team{" + "id=" + getId() + ", name='" + getName() + "'" + ", image='" + getImage() + "'" + "}";
    }
}
